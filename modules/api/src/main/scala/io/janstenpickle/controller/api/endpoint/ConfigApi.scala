package io.janstenpickle.controller.api.endpoint

import cats.data.ValidatedNel
import cats.effect.{Concurrent, Timer}
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import cats.syntax.functor._
import cats.syntax.apply._
import cats.~>
import extruder.circe._
import extruder.refined._
import fs2.Stream
import fs2.concurrent.Topic
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.api.trace.Http4sUtils
import io.janstenpickle.controller.api.view.ConfigView
import io.janstenpickle.controller.api.{UpdateTopics, ValidatingOptionalQueryParamDecoderMatcher}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.model._
import natchez.Trace
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame.Text
import org.http4s.{EntityEncoder, HttpRoutes, ParseFailure, QueryParamDecoder, QueryParameterValue, Request, Response}

import scala.concurrent.duration._

class ConfigApi[F[_]: Timer, G[_]: Concurrent: Timer](view: ConfigView[F], updateTopics: UpdateTopics[F])(
  implicit F: Concurrent[F],
  fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError],
  trace: Trace[F],
  liftLower: ContextualLiftLower[G, F, String]
) extends Common[F] {

  import ConfigApi._

  implicit val activitiesEncoder: EntityEncoder[F, Activities] = extruderEncoder[Activities]
  implicit val remotesEncoder: EntityEncoder[F, Remotes] = extruderEncoder[Remotes]
  implicit val buttonsEncoder: EntityEncoder[F, Buttons] = extruderEncoder[Buttons]
  implicit val roomsEncoder: EntityEncoder[F, Rooms] = extruderEncoder[Rooms]

  private def stream[A: DSEncoder](
    req: Request[F],
    topic: Topic[F, Boolean],
    op: () => F[A],
    errorMap: ControlError => A
  )(interval: FiniteDuration): F[Response[F]] = {
    val lowerName: F ~> G = liftLower.lower(req.uri.path)

    val stream = Stream
      .fixedRate[G](interval)
      .map(_ => true)
      .mergeHaltBoth(topic.subscribe(1).translate(lowerName))
      .evalMap(_ => lowerName(trace.put(Http4sUtils.requestFields(req): _*) *> ah.handle(op())(errorMap)))
      .map(as => Text(encode(as).noSpaces))

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
    case GET -> Root / "activities" => Ok(view.getActivities)
    case req @ GET -> Root / "activities" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        stream(req, updateTopics.activities, () => view.getActivities, err => Activities(List.empty, List(err.message)))
      )
    case GET -> Root / "remotes" => Ok(view.getRemotes)
    case req @ GET -> Root / "remotes" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        stream(req, updateTopics.remotes, () => view.getRemotes, err => Remotes(List.empty, List(err.message)))
      )
    case GET -> Root / "buttons" => Ok(view.getCommonButtons)
    case req @ GET -> Root / "buttons" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        stream(req, updateTopics.buttons, () => view.getCommonButtons, err => Buttons(List.empty, List(err.message)))
      )
    case GET -> Root / "rooms" => Ok(view.getRooms)
    case req @ GET -> Root / "rooms" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        stream(req, updateTopics.rooms, () => view.getRooms, err => Rooms(List.empty, List(err.message)))
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
