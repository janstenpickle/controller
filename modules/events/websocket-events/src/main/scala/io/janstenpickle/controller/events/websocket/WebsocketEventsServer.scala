package io.janstenpickle.controller.events.websocket

import cats.effect.concurrent.Deferred
import cats.effect.{BracketThrow, Concurrent, Timer}
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.~>
import fs2.concurrent.Queue
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import io.janstenpickle.controller.events.{Event, EventPublisher, EventSubscriber}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Text
import org.http4s.{HttpRoutes, Request}

import scala.concurrent.duration._

object WebsocketEventsServer {
  def receive[F[_]: Concurrent, G[_]: BracketThrow, A: Decoder](
    path: String,
    publisher: EventPublisher[F, A],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): HttpRoutes[F] = {
    val pipe: Queue[F, String] => Pipe[F, String, Unit] = q =>
      _.evalMap { str =>
        parse(str).flatMap(_.as[Event[A]]) match {
          case Left(error) => q.enqueue1(error.getMessage)
          case Right(event) =>
            publisher.publish1Event(event)
        }
    }

    make[F, G](path, "publish", pipe, Stream.empty, k)
  }

  def send[F[_]: Concurrent: Timer, G[_]: BracketThrow, A: Encoder](
    path: String,
    subscriber: EventSubscriber[F, A],
    k: ResourceKleisli[G, SpanName, Span[G]],
    state: Option[Deferred[F, F[List[Event[A]]]]] = None
  )(implicit provide: Provide[G, F, Span[G]]): HttpRoutes[F] = {
    val out =
      state
        .fold(subscriber.subscribeEvent) { state =>
          val periodic = Stream.awakeEvery[F](10.minutes).flatMap(_ => Stream.evals(state.get.flatten))

          (Stream.evals(state.get.flatten) ++ subscriber.subscribeEvent.drop(1)).merge(periodic)
        }
        .map(_.asJson.noSpacesSortKeys)

    make[F, G](path, "subscribe", _ => _.void, out, k)
  }

  private def make[F[_]: Concurrent, G[_]: BracketThrow](
    path: String,
    suffix: String,
    in: Queue[F, String] => Pipe[F, String, Unit],
    out: Stream[F, String],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    def pipe(q: Queue[F, String]): Pipe[F, WebSocketFrame, Unit] =
      _.map {
        case Text(str, _) => Some(str)
        case _ => None
      }.unNone.through(in(q))

    def toWebsocket(request: Request[F]) = {
      val lowerName: F ~> G = new (F ~> G) {
        override def apply[A](fa: F[A]): G[A] = k.run(request.uri.path).use(provide.provide(fa))
      }

      for {
        q <- Queue.circularBuffer[F, String](10)
        ws <- provide.lift(
          WebSocketBuilder[G]
            .build(
              q.dequeue
                .mergeHaltBoth(out)
                .map(Text(_))
                .translate(lowerName),
              stream => stream.translate(provide.liftK).through(pipe(q)).translate(lowerName)
            )
        )
      } yield ws.mapK(provide.liftK)
    }

    HttpRoutes.of[F] {
      case req @ GET -> Root / `path` / `suffix` => toWebsocket(req)
    }
  }
}
