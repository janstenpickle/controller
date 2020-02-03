package io.janstenpickle.controller.api.endpoint

import cats.data.ValidatedNel
import cats.effect.{Concurrent, Resource, Timer}
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.apply._
import cats.effect.syntax.concurrent._
import cats.{~>, Semigroupal}
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe._
import extruder.refined._
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.api.trace.Http4sUtils
import io.janstenpickle.controller.api.service.ConfigService
import io.janstenpickle.controller.api.ValidatingOptionalQueryParamDecoderMatcher
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.ConfigResult
import io.janstenpickle.controller.events.{EventPubSub, EventSubscriber}
import io.janstenpickle.controller.model._
import io.janstenpickle.controller.model.event.ConfigEvent._
import io.janstenpickle.controller.model.event.{ActivityUpdateEvent, ConfigEvent, SwitchEvent}
import natchez.Trace
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame.Text
import org.http4s.{HttpRoutes, ParseFailure, QueryParamDecoder, QueryParameterValue, Request, Response}

import scala.concurrent.duration._

class ConfigApi[F[_]: Timer, G[_]: Concurrent: Timer](
  service: ConfigService[F],
  activityPubSub: EventPubSub[F, ActivityUpdateEvent],
  configEventPubSub: EventPubSub[F, ConfigEvent],
  switchEventPubSub: EventPubSub[F, SwitchEvent]
)(
  implicit F: Concurrent[F],
  fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError],
  trace: Trace[F],
  liftLower: ContextualLiftLower[G, F, String]
) extends Common[F] {
  import ConfigApi._

  private def subscriber[A: DSEncoder](
    req: Request[F],
    subscriptionStream: Stream[F, Boolean],
    op: F[A],
    errorMap: ControlError => A
  )(interval: FiniteDuration) = {
    val lowerName: F ~> G = liftLower.lower(req.uri.path)

    val stream = Stream
      .fixedRate[F](interval)
      .map(_ => true)
      .mergeHaltBoth(subscriptionStream.groupWithin(1000, 50.millis).map(_ => true))
      .evalMap(_ => trace.put(Http4sUtils.requestFields(req): _*) *> ah.handle(op)(errorMap))
      .map(as => Text(encode(as).noSpaces))
      .translate(lowerName)

    liftLower
      .lift(
        WebSocketBuilder[G]
          .build(
            stream,
            _.map(_ => ()) // throw away input from listener
          )
      )
      .map(_.mapK(liftLower.lift))
  }

  private def intervalOrBadRequest(
    validated: ValidatedNel[ParseFailure, Option[FiniteDuration]],
    op: FiniteDuration => F[Response[F]]
  ): F[Response[F]] =
    validated
      .fold(failures => BadRequest(failures.map(_.message).toList.mkString(",")), i => op(i.getOrElse(20.seconds)))

  def refineOrBadReq(room: String, name: String)(
    f: (NonEmptyString, NonEmptyString) => F[Response[F]]
  ): F[Response[F]] =
    Semigroupal
      .map2[ValidatedNel[String, *], NonEmptyString, NonEmptyString, F[Response[F]]](
        refineV[NonEmpty](room).toValidatedNel,
        refineV[NonEmpty](name).toValidatedNel
      )(f)
      .leftMap(errs => BadRequest(errs.toList.mkString(",")))
      .merge

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ PUT -> Root / "activity" / a => Ok(req.as[Activity].flatMap(service.updateActivity(a, _)))
    case req @ POST -> Root / "activity" => Ok(req.as[Activity].flatMap(service.addActivity))
    case DELETE -> Root / "activity" / r / a =>
      refineOrBadReq(r, a) { (room, activity) =>
        Ok(service.deleteActivity(room, activity))
      }
    case GET -> Root / "activities" => Ok(service.getActivities)
    case req @ GET -> Root / "activities" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        subscriber(
          req,
          configEventPubSub.subscriberStream
            .collect {
              case ActivityAddedEvent(_, _) => true
              case ActivityRemovedEvent(_, _, _) => true
            }
            .subscribe
            .mergeHaltBoth(activityPubSub.subscriberStream.subscribe.map(_ => true)),
          service.getActivities,
          err => ConfigResult[String, Activity](Map.empty, List(err.message))
        )
      )
    case req @ POST -> Root / "remote" =>
      Ok(req.as[Remote].flatMap(service.addRemote))
    case req @ PUT -> Root / "remote" / r =>
      refineOrBadReq(r) { remoteName =>
        Ok(req.as[Remote].flatMap(service.updateRemote(remoteName, _)))
      }
    case DELETE -> Root / "remote" / r =>
      refineOrBadReq(r) { remote =>
        Ok(service.deleteRemote(remote))
      }
    case GET -> Root / "remotes" => Ok(service.getRemotes)
    case req @ GET -> Root / "remotes" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        subscriber(
          req,
          configEventPubSub.subscriberStream
            .collect {
              case ConfigEvent.RemoteAddedEvent(_, _) => true
              case ConfigEvent.RemoteRemovedEvent(_, _) => true
            }
            .subscribe
            .mergeHaltBoth(switchEventPubSub.subscriberStream.subscribe.map(_ => true)),
          service.getRemotes,
          err => ConfigResult[NonEmptyString, Remote](Map.empty, List(err.message))
        )
      )
    case req @ PUT -> Root / "button" / b =>
      refineOrBadReq(b) { button =>
        Ok(req.as[Button].flatMap(service.updateCommonButton(button, _)))
      }
    case req @ POST -> Root / "button" => Ok(req.as[Button].flatMap(service.addCommonButton))
    case DELETE -> Root / "button" / b =>
      refineOrBadReq(b) { button =>
        Ok(service.deleteCommonButton(button))
      }
    case GET -> Root / "buttons" => Ok(service.getCommonButtons)
    case req @ GET -> Root / "buttons" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        subscriber(
          req,
          configEventPubSub.subscriberStream
            .collect {
              case ButtonAddedEvent(_, _) => true
              case ButtonRemovedEvent(_, _, _) => true
            }
            .subscribe
            .mergeHaltBoth(switchEventPubSub.subscriberStream.subscribe.map(_ => true)),
          service.getCommonButtons,
          err => ConfigResult[String, Button](Map.empty, List(err.message))
        )
      )
    case GET -> Root / "rooms" => Ok(service.getRooms)
    case req @ GET -> Root / "rooms" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        subscriber(
          req,
          configEventPubSub.subscriberStream
            .collect {
              case ButtonAddedEvent(_, _) => true
              case ButtonRemovedEvent(_, _, _) => true
              case ActivityAddedEvent(_, _) => true
              case ActivityRemovedEvent(_, _, _) => true
              case RemoteAddedEvent(_, _) => true
              case RemoteRemovedEvent(_, _) => true
            }
            .subscribe
            .mergeHaltBoth(switchEventPubSub.subscriberStream.subscribe.map(_ => true)),
          service.getRooms,
          err => Rooms(List.empty, List(err.message))
        )
      )
  }
}

object ConfigApi {
  implicit def durationQueryParamDecoder: QueryParamDecoder[FiniteDuration] =
    new QueryParamDecoder[FiniteDuration] {
      override def decode(value: QueryParameterValue): ValidatedNel[ParseFailure, FiniteDuration] =
        QueryParamDecoder[Long]
          .map(_.seconds)
          .decode(value)
    }

  object OptionalDurationParamMatcher extends ValidatingOptionalQueryParamDecoderMatcher[FiniteDuration]("interval")
}
