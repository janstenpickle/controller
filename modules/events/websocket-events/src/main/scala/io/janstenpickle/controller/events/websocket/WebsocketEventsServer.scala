package io.janstenpickle.controller.events.websocket

import cats.effect.concurrent.Deferred
import cats.effect.{Clock, Concurrent}
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Applicative}
import fs2.concurrent.Queue
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.{Event, EventPublisher, EventSubscriber}
import natchez.Trace
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Text
import org.http4s.{HttpRoutes, Request}

object WebsocketEventsServer {
  def receive[F[_]: Concurrent, G[_]: Applicative, A: Decoder](
    path: String,
    publisher: EventPublisher[F, A]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] = {
    val pipe: Queue[F, String] => Pipe[F, String, Unit] = q =>
      _.evalMap { str =>
        parse(str).flatMap(_.as[Event[A]]) match {
          case Left(error) => q.enqueue1(error.getMessage)
          case Right(event) =>
            publisher.publish1Event(event)
        }
    }

    make[F, G](path, "publish", pipe, Stream.empty)
  }

  def send[F[_]: Concurrent: Clock, G[_]: Applicative, A: Encoder](
    path: String,
    subscriber: EventSubscriber[F, A],
    state: Option[Deferred[F, F[List[Event[A]]]]] = None
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] = {
    val out =
      state
        .fold(subscriber.subscribeEvent.drop(1)) { state =>
          Stream.evals(state.get.flatten) ++ subscriber.subscribeEvent.drop(1)
        }
        .map(_.asJson.noSpacesSortKeys)

    make[F, G](path, "subscribe", _ => _.void, out)
  }

  private def make[F[_]: Concurrent, G[_]: Applicative](
    path: String,
    suffix: String,
    in: Queue[F, String] => Pipe[F, String, Unit],
    out: Stream[F, String]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    def pipe(q: Queue[F, String]): Pipe[F, WebSocketFrame, Unit] =
      _.map {
        case Text(str, _) => Some(str)
        case _ => None
      }.unNone.through(in(q))

    def toWebsocket(request: Request[F]) = {
      val lowerName: F ~> G = liftLower.lower(request.uri.path)

      for {
        q <- Queue.circularBuffer[F, String](10)
        ws <- liftLower.lift(
          WebSocketBuilder[G]
            .build(
              q.dequeue
                .mergeHaltBoth(out)
                .map(Text(_))
                .translate(lowerName),
              stream => stream.translate(liftLower.lift).through(pipe(q)).translate(lowerName)
            )
        )
      } yield ws.mapK(liftLower.lift)
    }

    HttpRoutes.of[F] {
      case req @ GET -> Root / `path` / `suffix` => toWebsocket(req)
    }
  }
}
