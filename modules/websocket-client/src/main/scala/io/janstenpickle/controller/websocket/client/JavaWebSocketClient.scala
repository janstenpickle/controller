package io.janstenpickle.controller.websocket.client

import cats.data.Kleisli
import cats.effect.syntax.concurrent._
import cats.effect.{Async, Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadError}
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.janstenpickle.trace4cats.base.context.Provide
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.slf4j.LoggerFactory

import java.net.URI
import java.nio.ByteBuffer
import scala.concurrent.duration._

class JavaWebSocketClient[F[_]: ContextShift, G[_], Ctx](
  serverUri: URI,
  stringReceiver: Option[String => F[Unit]],
  bytesReceiver: Option[ByteBuffer => F[Unit]],
  signal: Option[SignallingRef[F, Boolean]],
  blocker: Blocker,
  k: Kleisli[Resource[G, *], String, Ctx],
)(implicit F: MonadError[F, Throwable], G: ConcurrentEffect[G], provide: Provide[G, F, Ctx])
    extends WebSocketClient(serverUri) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  private def lowerName[A](fa: F[A]) = k.run(serverUri.toString).use(provide.provide(fa))

  override def onOpen(handshakedata: ServerHandshake): Unit = {
    logger.info(s"Starting websocket connection to $uri")

    signal.foreach { sig =>
      G.toIO(lowerName(sig.set(false))).unsafeRunAsyncAndForget()
    }
  }

  override def onClose(code: Int, reason: String, remote: Boolean): Unit = {
    logger.info(s"Websocket connection to $uri closed")
    signal.foreach { sig =>
      G.toIO(lowerName(sig.set(true))).unsafeRunAsyncAndForget()
    }
  }

  override def onMessage(message: String): Unit =
    stringReceiver.foreach { rec =>
      G.toIO(lowerName(blocker.blockOn(rec(message)))).unsafeRunAsyncAndForget()
    }

  override def onMessage(bytes: ByteBuffer): Unit =
    bytesReceiver.foreach { rec =>
      G.toIO(lowerName(blocker.blockOn(rec(bytes)))).unsafeRunAsyncAndForget()
    }

  override def onError(ex: Exception): Unit =
    logger.error(s"Websocket connection to $uri failed", ex)
}

object JavaWebSocketClient {
  private def retry[F[_]: Concurrent: Timer, A](fa: F[A]): F[A] =
    fa.tailRecM(
      _.map[Either[F[A], A]](_ => Left(Timer[F].sleep(5.seconds) >> fa))
        .handleError(_ => Left(Timer[F].sleep(5.seconds) >> fa))
    )

  private def retryResource[F[_]: Concurrent: Timer, A](fa: F[A]) = retry(fa).background

  private def monitorWebsocket[F[_]: Concurrent: ContextShift: Timer, G[_], Ctx](
    ws: JavaWebSocketClient[F, G, Ctx],
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

  private def receive[F[_]: Concurrent: Timer: ContextShift, G[_]: ConcurrentEffect, Ctx](
    uri: URI,
    blocker: Blocker,
    k: Kleisli[Resource[G, *], String, Ctx],
    receiver: Either[String => F[Unit], ByteBuffer => F[Unit]],
  )(implicit provide: Provide[G, F, Ctx]) = {
    def makeWs =
      receiver.fold(
        stringRec => new JavaWebSocketClient(uri, Some(stringRec), None, None, blocker, k),
        bytesRec => new JavaWebSocketClient(uri, None, Some(bytesRec), None, blocker, k)
      )

    (for {
      ws <- Resource
        .make(Sync[F].delay(makeWs))(ws => Sync[F].delay(ws.close()))
      _ <- Resource.liftF(Sync[F].delay(ws.connect()))
      _ <- monitorWebsocket[F, G, Ctx](ws, blocker)
    } yield ()).use(_ => Async[F].never[Unit]).background
  }

  def receiveString[F[_]: Concurrent: Timer: ContextShift, G[_]: ConcurrentEffect, Ctx](
    uri: URI,
    blocker: Blocker,
    k: Kleisli[Resource[G, *], String, Ctx],
    stringReceiver: String => F[Unit],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Unit]] =
    receive[F, G, Ctx](uri, blocker, k, Left(stringReceiver))

  def receiveBytes[F[_]: Concurrent: Timer: ContextShift, G[_]: ConcurrentEffect, Ctx](
    uri: URI,
    blocker: Blocker,
    k: Kleisli[Resource[G, *], String, Ctx],
    bytesReceiver: ByteBuffer => F[Unit],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Unit]] =
    receive[F, G, Ctx](uri, blocker, k, Right(bytesReceiver))

  private def send[F[_]: Concurrent: Timer: ContextShift, G[_]: ConcurrentEffect, A, Ctx](
    uri: URI,
    stream: Stream[F, A],
    doSend: (JavaWebSocketClient[F, G, Ctx], A) => Unit,
    blocker: Blocker,
    k: Kleisli[Resource[G, *], String, Ctx],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Unit]] = {

    def sender(a: A, ws: JavaWebSocketClient[F, G, Ctx]): F[Unit] = {
      def isOpen = blocker.delay[F, Boolean](ws.isOpen)

      isOpen.tailRecM(
        _.flatMap[Either[F[Boolean], Unit]](
          if (_)
            blocker
              .delay[F, Unit](doSend(ws, a))
              .map(Right(_))
          else Applicative[F].pure(Left(Timer[F].sleep(2.seconds) >> isOpen))
        )
      )
    }

    (for {
      signal <- Resource.liftF(SignallingRef[F, Boolean](false))
      ws <- Resource
        .make(Sync[F].delay(new JavaWebSocketClient(uri, None, None, Some(signal), blocker, k)))(
          ws => Sync[F].delay(ws.close())
        )
      _ <- Resource.liftF(Sync[F].delay(ws.connect()))
      _ <- retryResource(stream.evalMap(sender(_, ws)).interruptWhen(signal).compile.drain)
      _ <- monitorWebsocket[F, G, Ctx](ws, blocker)
    } yield ws).use(_ => Async[F].never[Unit]).background
  }

  def sendString[F[_]: Concurrent: Timer: ContextShift, G[_]: ConcurrentEffect, Ctx](
    uri: URI,
    blocker: Blocker,
    stringStream: Stream[F, String],
    k: Kleisli[Resource[G, *], String, Ctx],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Unit]] =
    send[F, G, String, Ctx](uri, stringStream, (ws, a) => ws.send(a), blocker, k)

  def sendBytes[F[_]: Concurrent: Timer: ContextShift, G[_]: ConcurrentEffect, Ctx](
    uri: URI,
    blocker: Blocker,
    byteStream: Stream[F, ByteBuffer],
    k: Kleisli[Resource[G, *], String, Ctx],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Unit]] =
    send[F, G, ByteBuffer, Ctx](uri, byteStream, (ws, a) => ws.send(a), blocker, k)
}
