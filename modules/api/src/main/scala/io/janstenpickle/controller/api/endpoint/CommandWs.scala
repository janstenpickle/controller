package io.janstenpickle.controller.api.endpoint

import cats.effect.Concurrent
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import fs2.concurrent.Queue
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.arrow.ContextualLiftLower
import natchez.Trace
import org.http4s.HttpRoutes
import org.http4s.server.websocket.WebSocketBuilder
import fs2.Pipe
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.CommandEvent
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Text
import io.circe.parser._
import io.circe.generic.extras.auto._
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import cats.syntax.flatMap._
import cats.syntax.functor._

class CommandWs[F[_], G[_]: Concurrent](publisher: EventPublisher[G, CommandEvent])(
  implicit F: Concurrent[F],
  fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError],
  trace: Trace[F],
  liftLower: ContextualLiftLower[G, F, String]
) extends Common[F] {

  implicit val config: Configuration = Configuration.default.withDiscriminator("type")

  implicit val commandEventCodec: Codec.AsObject[CommandEvent] = deriveConfiguredCodec[CommandEvent]

  def pipe(q: Queue[G, String]): Pipe[G, WebSocketFrame, Unit] =
    _.map {
      case Text(str, _) => Some(str)
      case _ => None
    }.unNone.evalMap { str =>
      parse(str).flatMap(_.as[CommandEvent]) match {
        case Left(error) => q.enqueue1(error.getMessage)
        case Right(event) =>
          publisher.publish1(event) >> q.enqueue1("OK")
      }
    }

  val ws = liftLower
    .lift(for {
      q <- Queue.circularBuffer[G, String](10)
      ws <- WebSocketBuilder[G].build(q.dequeue.map(Text(_)), pipe(q))
    } yield ws)
    .map(_.mapK(liftLower.lift))

  val routes: HttpRoutes[F] = HttpRoutes.of {
    case GET -> Root / "ws" => ws
  }
}
