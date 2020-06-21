package io.janstenpickle.controller.events.websocket

import java.net.URI

import cats.effect.concurrent.Deferred
import cats.effect.syntax.concurrent._
import cats.effect.{Async, Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.instances.list._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Applicative, MonadError}
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.{Event, EventPublisher, EventSubscriber}
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

class JavaWebsocket[F[_]: ContextShift, G[_], A: Decoder](
  serverUri: URI,
  publisher: Option[EventPublisher[F, A]],
  signal: Option[SignallingRef[F, Boolean]],
  blocker: Blocker,
)(implicit F: MonadError[F, Throwable], G: ConcurrentEffect[G], liftLower: ContextualLiftLower[G, F, String])
    extends WebSocketClient(serverUri) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val lowerName: F ~> G = liftLower.lower(serverUri.toString)

  override def onOpen(handshakedata: ServerHandshake): Unit = {
    logger.info(s"Starting event websocket connection to $uri")

    signal.foreach { sig =>
      G.toIO(lowerName(sig.set(false))).unsafeRunAsyncAndForget()
    }
  }

  override def onClose(code: Int, reason: String, remote: Boolean): Unit = {
    logger.info(s"Event websocket to $uri closed")
    signal.foreach { sig =>
      G.toIO(lowerName(sig.set(true))).unsafeRunAsyncAndForget()
    }
  }

  override def onMessage(message: String): Unit =
    publisher.foreach { pub =>
      G.toIO(
          lowerName(
            blocker
              .blockOn(F.fromEither(parse(message).flatMap(_.as[Event[A]])).flatMap(pub.publish1Event))
          )
        )
        .unsafeRunAsyncAndForget()
    }

  override def onError(ex: Exception): Unit =
    logger.error(s"Event websocket to $uri failed", ex)
}

object JavaWebsocket {
  private def retry[F[_]: Concurrent: Timer, A](fa: F[A]): F[A] =
    fa.tailRecM(
      _.map[Either[F[A], A]](_ => Left(Timer[F].sleep(5.seconds) >> fa))
        .handleError(_ => Left(Timer[F].sleep(5.seconds) >> fa))
    )

  private def retryResource[F[_]: Concurrent: Timer, A](fa: F[A]) = retry(fa).background

  private def monitorWebsocket[F[_]: Concurrent: ContextShift: Timer, G[_], A](
    ws: JavaWebsocket[F, G, A],
    blocker: Blocker
  ) =
    retryResource(
      Stream
        .awakeEvery[F](10.seconds)
        .evalMap[F, Unit](
          _ =>
            blocker
              .delay[F, Boolean](ws.isOpen)
              .ifM(Applicative[F].unit, blocker.delay[F, Unit](ws.reconnect()))
        )
        .compile
        .drain
    )

  def receive[F[_]: Concurrent: Timer: ContextShift, G[_]: ConcurrentEffect, A: Encoder: Decoder](
    host: NonEmptyString,
    port: PortNumber,
    path: String,
    blocker: Blocker,
    publisher: EventPublisher[F, A]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, F[Unit]] = {
    val uri = new URI(s"ws://$host:$port/events/$path/subscribe")

    (for {
      ws <- Resource
        .make(Sync[F].delay(new JavaWebsocket(uri, Some(publisher), None, blocker)))(ws => Sync[F].delay(ws.close()))
      _ <- Resource.liftF(Sync[F].delay(ws.connect()))
      _ <- monitorWebsocket[F, G, A](ws, blocker)
    } yield ()).use(_ => Async[F].never[Unit]).background
  }

  def send[F[_]: Concurrent: Timer: ContextShift, G[_]: ConcurrentEffect, A: Encoder: Decoder](
    host: NonEmptyString,
    port: PortNumber,
    path: String,
    blocker: Blocker,
    subscriber: EventSubscriber[F, A],
    state: Option[Deferred[F, F[List[Event[A]]]]] = None
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, F[Unit]] = {
    val uri = new URI(s"ws://$host:$port/events/$path/publish")

    def sendEvent(event: Event[A], ws: JavaWebsocket[F, G, A]) = {
      def isOpen = blocker.delay[F, Boolean](ws.isOpen)

      isOpen.tailRecM(
        _.flatMap[Either[F[Boolean], Unit]](
          if (_)
            blocker
              .delay[F, Unit](ws.send(event.asJson.noSpacesSortKeys))
              .map(Right(_))
          else Applicative[F].pure(Left(Timer[F].sleep(2.seconds) >> isOpen))
        )
      )
    }

    def sendState(ws: JavaWebsocket[F, G, A], signal: SignallingRef[F, Boolean]) =
      state
        .fold(subscriber.subscribeEvent)(state => Stream.evals(state.get.flatten) ++ subscriber.subscribeEvent)
        .evalMap(sendEvent(_, ws))
        .interruptWhen(signal)
        .compile
        .drain

    def periodicDumpState(ws: JavaWebsocket[F, G, A]) =
      state.fold(Resource.pure[F, Unit](())) { state =>
        retryResource(
          Stream
            .awakeEvery[F](10.minutes)
            .flatMap(_ => Stream.evals(state.get.flatten))
            .evalMap(sendEvent(_, ws))
            .compile
            .drain
        ).void
      }

    (for {
      signal <- Resource.liftF(SignallingRef[F, Boolean](false))
      ws <- Resource
        .make(Sync[F].delay(new JavaWebsocket(uri, None, Some(signal), blocker)))(ws => Sync[F].delay(ws.close()))
      _ <- Resource.liftF(Sync[F].delay(ws.connect()))
      _ <- retryResource(sendState(ws, signal))
      _ <- monitorWebsocket[F, G, A](ws, blocker)
      _ <- periodicDumpState(ws)
    } yield ws).use(_ => Async[F].never[Unit]).background
  }
}
