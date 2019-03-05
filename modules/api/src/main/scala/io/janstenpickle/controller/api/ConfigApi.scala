package io.janstenpickle.controller.api

import cats.data.{EitherT, ValidatedNel}
import cats.effect.{Concurrent, Timer}
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.~>
import extruder.circe._
import extruder.refined._
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.api.view.ConfigView
import io.janstenpickle.controller.model._
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame.Text
import org.http4s.{EntityEncoder, HttpRoutes, ParseFailure, QueryParamDecoder, QueryParameterValue, Response}

import scala.concurrent.duration._

class ConfigApi[F[_]: Timer](
  view: ConfigView[EitherT[F, ControlError, ?]],
  updateTopics: UpdateTopics[EitherT[F, ControlError, ?]]
)(implicit F: Concurrent[F])
    extends Common[F] {
  import ConfigApi._

  implicit val activitiesEncoder: EntityEncoder[F, Activities] =
    extruderEncoder[Activities]
  implicit val remotesEncoder: EntityEncoder[F, Remotes] = extruderEncoder[Remotes]
  implicit val buttonsEncoder: EntityEncoder[F, Buttons] = extruderEncoder[Buttons]

  type ET[A] = EitherT[F, ControlError, A]

  val translateF: ET ~> F = new (ET ~> F) {
    override def apply[A](fa: ET[A]): F[A] = fa.value.flatMap {
      case Left(err) => F.raiseError(err)
      case Right(a) => F.pure(a)
    }
  }

  private def stream[A: DSEncoder](topic: Topic[ET, Boolean], op: () => ET[A], errorMap: ControlError => A)(
    interval: FiniteDuration
  ): F[Response[F]] =
    for {
      queue <- Queue.circularBuffer[F, Boolean](1) // Use a queue implementation which does not block on a slow consumer
      // start a fiber which consumes from the topic and sends to the non-blocking queue
      fiber <- F.start(
        topic
          .subscribe(1)
          .translate(translateF)
          .through(queue.enqueue)
          .compile
          .drain
      )

      stream = Stream
        .fixedRate(interval)
        .map(_ => true)
        .merge(queue.dequeue)
        .evalMap(_ => op().value)
        .map(_.leftMap(errorMap).merge)
        .map(as => Text(encode(as).noSpaces))

      // create a websocket which reads from the queue and stops the fiber when the connection is closed
      resp <- WebSocketBuilder[F]
        .build(
          stream,
          _.map(_ => ()), // throw away input from listener and stop the stream after a defined duration
          onClose = fiber.cancel // stop the topic -> queue fiber when the socket gets closed
        )
    } yield resp

  private def intervalOrBadRequest(
    validated: ValidatedNel[ParseFailure, Option[FiniteDuration]],
    op: FiniteDuration => F[Response[F]]
  ): F[Response[F]] =
    validated
      .fold(failures => BadRequest(failures.map(_.message).toList.mkString(",")), i => op(i.getOrElse(20.seconds)))

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "activities" => handleControlError(view.getActivities)
    case GET -> Root / "activities" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        stream(updateTopics.activities, () => view.getActivities, err => Activities(List.empty, List(err.message)))
      )
    case GET -> Root / "remotes" => handleControlError(view.getRemotes)
    case GET -> Root / "remotes" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        stream(updateTopics.remotes, () => view.getRemotes, err => Remotes(List.empty, List(err.message)))
      )
    case GET -> Root / "buttons" => handleControlError(view.getCommonButtons)
    case GET -> Root / "buttons" / "ws" :? OptionalDurationParamMatcher(interval) =>
      intervalOrBadRequest(
        interval,
        stream(updateTopics.buttons, () => view.getCommonButtons, err => Buttons(List.empty, List(err.message)))
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
