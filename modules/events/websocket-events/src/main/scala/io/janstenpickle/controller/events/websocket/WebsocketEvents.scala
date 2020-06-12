package io.janstenpickle.controller.events.websocket

import cats.effect.Concurrent
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Applicative, Defer}
import fs2.Pipe
import fs2.concurrent.Queue
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import natchez.Trace
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Text
import org.http4s.{HttpRoutes, Request}
import fs2.Stream
import cats.instances.list._

object WebsocketEvents {
  def subscribe[F[_]: Applicative: Defer, G[_]: Applicative, A: Encoder](
    path: String,
    subscriber: EventSubscriber[F, A],
    state: Option[F[List[A]]] = None
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    def toWebsocket(request: Request[F]) = {
      val lowerName: F ~> G = liftLower.lower(request.uri.path)

      val source = state.fold(subscriber.subscribe)(Stream.evals(_) ++ subscriber.subscribe)

      val stream = source
        .map { a =>
          Text(a.asJson.noSpaces)
        }
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

    HttpRoutes.of[F] {
      case req @ GET -> Root / `path` / "subscribe" => toWebsocket(req)
    }
  }

  def publish[F[_]: Concurrent, G[_]: Applicative, A: Decoder](
    path: String,
    publisher: EventPublisher[F, A]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    def pipe(q: Queue[F, String]): Pipe[F, WebSocketFrame, Unit] =
      _.map {
        case Text(str, _) => Some(str)
        case _ => None
      }.unNone.evalMap { str =>
        parse(str).flatMap(_.as[A]) match {
          case Left(error) => q.enqueue1(error.getMessage)
          case Right(event) =>
            publisher.publish1(event) >> q.enqueue1("OK")
        }
      }

    def toWebsocket(request: Request[F]) = {
      val lowerName: F ~> G = liftLower.lower(request.uri.path)

      for {
        q <- Queue.circularBuffer[F, String](10)
        ws <- liftLower.lift(
          WebSocketBuilder[G]
            .build(
              q.dequeue.map(Text(_)).translate(lowerName),
              stream => stream.translate(liftLower.lift).through(pipe(q)).translate(lowerName)
            )
        )
      } yield ws.mapK(liftLower.lift)
    }

    HttpRoutes.of[F] {
      case req @ GET -> Root / `path` / "publish" => toWebsocket(req)
    }
  }
}
