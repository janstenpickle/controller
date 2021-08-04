package io.janstenpickle.controller.api.endpoint

import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.effect.{Clock, Concurrent}
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.{Pipe, Stream}
import io.circe.parser._
import io.janstenpickle.controller.events.{Event, EventPublisher}
import io.janstenpickle.controller.http4s.error.ControlError
import io.janstenpickle.controller.model.event.CommandEvent
import io.janstenpickle.trace4cats.base.context.Lift
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Text
import org.http4s.{HttpRoutes, Response}

class CommandWs[F[_], G[_]: Concurrent: Clock](publisher: EventPublisher[G, CommandEvent], source: String)(
  implicit F: Async[F],
  fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError],
  lift: Lift[G, F]
) extends Common[F] {
  def pipe(q: Queue[G, String]): Pipe[G, WebSocketFrame, Unit] =
    _.map {
      case Text(str, _) => Some(str)
      case _ => None
    }.unNone.evalMap { str =>
      parse(str).flatMap(_.as[CommandEvent]) match {
        case Left(error) => q.offer(error.getMessage)
        case Right(event) =>
          Event[G, CommandEvent](event, source).flatMap(publisher.publish1Event) >> q.offer("OK")
      }
    }

  val ws: F[Response[F]] =
    lift
      .lift(for {
        q <- Queue.circularBuffer[G, String](10)
        ws <- WebSocketBuilder[G].build(Stream.fromQueueUnterminated(q).map(Text(_)), pipe(q))
      } yield ws)
      .map(_.mapK(lift.liftK))

  val routes: HttpRoutes[F] = HttpRoutes.of {
    case GET -> Root / "ws" => ws
  }
}
