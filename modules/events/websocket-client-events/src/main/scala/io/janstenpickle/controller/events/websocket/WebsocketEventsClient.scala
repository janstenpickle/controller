//package io.janstenpickle.controller.events.websocket
//
//import alleycats.std.set._
//import cats.effect.concurrent.Deferred
//import cats.effect.syntax.concurrent._
//import cats.effect.{Blocker, Clock, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
//import cats.instances.list._
//import cats.syntax.applicativeError._
//import cats.syntax.flatMap._
//import cats.syntax.functor._
//import cats.syntax.traverse._
//import cats.{~>, Applicative}
//import eu.timepit.refined.types.net.PortNumber
//import eu.timepit.refined.types.string.NonEmptyString
//import fs2.{Pipe, Stream}
//import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
//import io.circe.parser.parse
//import io.circe.syntax._
//import io.circe.{Decoder, Encoder}
//import io.janstenpickle.controller.arrow.ContextualLiftLower
//import io.janstenpickle.controller.events.{Event, EventPublisher, EventSubscriber}
//import natchez.Trace
//import sttp.client._
//import sttp.client.asynchttpclient.fs2.{AsyncHttpClientFs2Backend, Fs2WebSocketHandler}
//import sttp.client.impl.fs2.Fs2WebSockets
//
//import scala.concurrent.duration._
//
//object WebsocketEventsClient {
//  def receive[F[_]: Timer, G[_]: ContextShift, A: Decoder](
//    host: NonEmptyString,
//    port: PortNumber,
//    path: String,
//    blocker: Blocker,
//    publisher: EventPublisher[F, A]
//  )(
//    implicit F: Concurrent[F],
//    G: ConcurrentEffect[G],
//    liftLower: ContextualLiftLower[G, F, String],
//    timer: Timer[G],
//    trace: Trace[F]
//  ): Resource[F, Unit] = Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
//    val pipe: Pipe[F, String, Unit] =
//      _.evalMap[F, Option[Event[A]]] { str =>
//        parse(str).flatMap(_.as[Event[A]]) match {
//          case Left(error) => logger.warn(error)("Failed to decode incoming message").as(None)
//          case Right(event) => Applicative[F].pure(Some(event))
//        }
//      }.unNone.groupWithin(2, 10.millis).evalMap { chunk =>
//        Set.from(chunk.iterator).traverse(publisher.publish1Event).void
//      }
//
//    make[F, G](host, port, path, "subscribe", blocker, pipe, Stream.never[F])
//  }
//
//  def send[F[_]: Clock, G[_]: ContextShift, A: Encoder](
//    host: NonEmptyString,
//    port: PortNumber,
//    path: String,
//    blocker: Blocker,
//    subscriber: EventSubscriber[F, A],
//    state: Option[Deferred[F, F[List[Event[A]]]]] = None
//  )(
//    implicit F: Sync[F],
//    G: ConcurrentEffect[G],
//    liftLower: ContextualLiftLower[G, F, String],
//    timer: Timer[G],
//    trace: Trace[F]
//  ): Resource[F, Unit] = {
//    val subStream = state
//      .fold(subscriber.subscribeEvent.drop(1)) { state =>
//        Stream
//          .evals(trace.span("send.state")(state.get.flatten)) ++ subscriber.subscribeEvent
//          .drop(1)
//      }
//      .map(_.asJson.noSpacesSortKeys)
//
//    make[F, G](host, port, path, "publish", blocker, _.void, subStream)
//  }
//
//  private def make[F[_], G[_]: ContextShift](
//    host: NonEmptyString,
//    port: PortNumber,
//    path: String,
//    suffix: String,
//    blocker: Blocker,
//    publish: Pipe[F, String, Unit],
//    subscribe: Stream[F, String]
//  )(
//    implicit F: Sync[F],
//    G: ConcurrentEffect[G],
//    liftLower: ContextualLiftLower[G, F, String],
//    timer: Timer[G],
//    trace: Trace[F]
//  ): Resource[F, Unit] = {
//    val uri = uri"ws://$host:$port/events/$path/$suffix"
//
//    val lowerName: F ~> G = liftLower.lower(uri.toString())
//
//    Resource
//      .liftF(Slf4jLogger.create[G])
//      .flatMap { logger =>
//        def websocket: G[Unit] =
//          logger.info(s"Starting event websocket connection to $uri") >> blocker
//            .blockOn(
//              AsyncHttpClientFs2Backend[G]()
//                .flatMap { implicit backend =>
//                  basicRequest
//                    .get(uri)
//                    .openWebsocketF(Fs2WebSocketHandler[G]())
//                    .flatMap { response =>
//                      Fs2WebSockets.handleSocketThroughTextPipe(response.result) { in =>
//                        val receive = in.translate(liftLower.lift).through(publish).translate(lowerName)
//                        val send = subscribe.map(Right(_)).translate(lowerName)
//
//                        send.mergeHaltBoth(receive.drain)
//                      }
//                    }
//
//                }
//            )
//            .handleErrorWith { th =>
//              logger.error(th)(s"Event websocket to $uri failed, restarting") >> timer.sleep(10.seconds) >> websocket
//            } >> logger.info(s"Event websocket to $uri closed, restarting") >> timer.sleep(20.seconds) >> websocket
//
//        websocket.background.map(_ => ()) >> Resource.make(Applicative[G].unit)(
//          _ => logger.info(s"Shutting down websocket connection to $uri")
//        )
//      }
//      .mapK(liftLower.lift)
//  }
//
//}
