package io.janstenpickle.controller.api.endpoint

import cats.data.ValidatedNel
import cats.effect.{Concurrent, Timer}
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.apply._
import cats.~>
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe._
import extruder.refined._
import fs2.Stream
import fs2.concurrent.Topic
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.api.trace.Http4sUtils
import io.janstenpickle.controller.api.service.ConfigService
import io.janstenpickle.controller.api.{UpdateTopics, ValidatingOptionalQueryParamDecoderMatcher}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.ConfigResult
import io.janstenpickle.controller.model._
import natchez.Trace
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame.Text
import org.http4s.{HttpRoutes, ParseFailure, QueryParamDecoder, QueryParameterValue, Request, Response}

import scala.concurrent.duration._

class ConfigApi[F[_]: Timer, G[_]: Concurrent: Timer](service: ConfigService[F], updateTopics: UpdateTopics[F])(
  implicit F: Concurrent[F],
  fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError],
  trace: Trace[F],
  liftLower: ContextualLiftLower[G, F, String]
) extends Common[F] {
  import ConfigApi._

  private def stream[A: DSEncoder](
    req: Request[F],
    topic: Topic[F, Boolean],
    op: () => F[A],
    errorMap: ControlError => A
  )(interval: FiniteDuration): F[Response[F]] = {
    val lowerName: F ~> G = liftLower.lower(req.uri.path)

    val stream = Stream
      .fixedRate[F](interval)
      .map(_ => true)
      .mergeHaltBoth(topic.subscribe(1))
      .evalMap(_ => trace.put(Http4sUtils.requestFields(req): _*) *> ah.handle(op())(errorMap))
      .map(as => Text(encode(as).noSpaces))
      .translate(lowerName)

    // create a websocket which reads from the queue and stops the fiber when the connection is closed

    liftLower
      .lift(
        WebSocketBuilder[G]
          .build(
            stream,
            _.map(_ => ()) // throw away input from listener and stop the stream after a defined duration
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

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ PUT -> Root / "activity" / a => Ok(req.as[Activity].flatMap(service.updateActivity(a, _)))
    case req @ POST -> Root / "activity" => Ok(req.as[Activity].flatMap(service.addActivity))
    case DELETE -> Root / "activity" / a =>
      refineOrBadReq(a) { activity =>
        Ok(service.deleteActivity(activity))
      }
    case GET -> Root / "activities" => Ok(service.getActivities)
    case req @ GET -> Root / "activities" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        stream(
          req,
          updateTopics.activities,
          () => service.getActivities,
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
        stream(
          req,
          updateTopics.remotes,
          () => service.getRemotes,
          err => ConfigResult[NonEmptyString, Remote](Map.empty, List(err.message))
        )
      )
    case req @ PUT -> Root / "button" => Ok(req.as[Button].flatMap(service.addCommonButton))
    case DELETE -> Root / "button" / b =>
      refineOrBadReq(b) { button =>
        Ok(service.deleteCommonButton(button.value))
      }
    case GET -> Root / "buttons" => Ok(service.getCommonButtons)
    case req @ GET -> Root / "buttons" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        stream(
          req,
          updateTopics.buttons,
          () => service.getCommonButtons,
          err => ConfigResult[String, Button](Map.empty, List(err.message))
        )
      )
    case GET -> Root / "rooms" => Ok(service.getRooms)
    case req @ GET -> Root / "rooms" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        stream(req, updateTopics.rooms, () => service.getRooms, err => Rooms(List.empty, List(err.message)))
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
