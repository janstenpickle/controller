package io.janstenpickle.controller.websocket.client

import cats.data.Kleisli
import cats.effect.kernel.{Outcome, Temporal}
import cats.effect.std.Dispatcher
import cats.effect.syntax.spawn._
import cats.effect.{Async, MonadCancelThrow, Resource, Sync}
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

class JavaWebSocketClient[F[_], G[_]: MonadCancelThrow, Ctx](
  serverUri: URI,
  stringReceiver: Option[String => F[Unit]],
  bytesReceiver: Option[ByteBuffer => F[Unit]],
  signal: Option[SignallingRef[F, Boolean]],
  dispatcher: Dispatcher[G],
  k: Kleisli[Resource[G, *], String, Ctx],
)(implicit F: MonadError[F, Throwable], provide: Provide[G, F, Ctx])
    extends WebSocketClient(serverUri) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  private def lowerName[A](fa: F[A]) = k.run(serverUri.toString).use(provide.provide(fa))

  override def onOpen(handshakedata: ServerHandshake): Unit = {
    logger.info(s"Starting websocket connection to $uri")

    signal.foreach { sig =>
      dispatcher.unsafeRunAndForget(lowerName(sig.set(false)))
    }
  }

  override def onClose(code: Int, reason: String, remote: Boolean): Unit = {
    logger.info(s"Websocket connection to $uri closed")
    signal.foreach { sig =>
      dispatcher.unsafeRunAndForget(lowerName(sig.set(true)))
    }
  }

  override def onMessage(message: String): Unit =
    stringReceiver.foreach { rec =>
      dispatcher.unsafeRunAndForget(lowerName(rec(message)))
    }

  override def onMessage(bytes: ByteBuffer): Unit =
    bytesReceiver.foreach { rec =>
      dispatcher.unsafeRunAndForget(lowerName(rec(bytes)))
    }

  override def onError(ex: Exception): Unit =
    logger.error(s"Websocket connection to $uri failed", ex)
}

object JavaWebSocketClient {
  private def retry[F[_]: Temporal, A](fa: F[A]): F[A] =
    fa.tailRecM(
      _.map[Either[F[A], A]](_ => Left(Temporal[F].sleep(5.seconds) >> fa))
        .handleError(_ => Left(Temporal[F].sleep(5.seconds) >> fa))
    )

  private def retryResource[F[_]: Async, A](fa: F[A]) = retry(fa).background

  private def monitorWebsocket[F[_]: Async, G[_], Ctx](ws: JavaWebSocketClient[F, G, Ctx]) =
    retryResource(
      Stream
        .awakeEvery[F](10.seconds)
        .evalMap[F, Unit](
          _ =>
            Sync[F]
              .blocking(ws.isOpen)
              .ifM(Applicative[F].unit, Sync[F].blocking(ws.reconnect()))
        )
        .compile
        .drain
    )

  private def receive[F[_]: Async, G[_]: Async, Ctx](
    uri: URI,
    k: Kleisli[Resource[G, *], String, Ctx],
    receiver: Either[String => F[Unit], ByteBuffer => F[Unit]],
  )(implicit provide: Provide[G, F, Ctx]) = {
    def makeWs(dispatcher: Dispatcher[G]) =
      receiver.fold(
        stringRec => new JavaWebSocketClient(uri, Some(stringRec), None, None, dispatcher, k),
        bytesRec => new JavaWebSocketClient(uri, None, Some(bytesRec), None, dispatcher, k)
      )

    (for {
      dispatcher <- Dispatcher[G].mapK(provide.liftK)
      ws <- Resource
        .make(Sync[F].delay(makeWs(dispatcher)))(ws => Sync[F].delay(ws.close()))
      _ <- Resource.eval(Sync[F].delay(ws.connect()))
      _ <- monitorWebsocket[F, G, Ctx](ws)
    } yield ()).use(_ => Async[F].never[Unit]).background
  }

  def receiveString[F[_]: Async, G[_]: Async, Ctx](
    uri: URI,
    k: Kleisli[Resource[G, *], String, Ctx],
    stringReceiver: String => F[Unit],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Outcome[F, Throwable, Unit]]] =
    receive[F, G, Ctx](uri, k, Left(stringReceiver))

  def receiveBytes[F[_]: Async, G[_]: Async, Ctx](
    uri: URI,
    k: Kleisli[Resource[G, *], String, Ctx],
    bytesReceiver: ByteBuffer => F[Unit],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Outcome[F, Throwable, Unit]]] =
    receive[F, G, Ctx](uri, k, Right(bytesReceiver))

  private def send[F[_]: Async, G[_]: Async, A, Ctx](
    uri: URI,
    stream: Stream[F, A],
    doSend: (JavaWebSocketClient[F, G, Ctx], A) => Unit,
    k: Kleisli[Resource[G, *], String, Ctx],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Outcome[F, Throwable, Unit]]] = {

    def sender(a: A, ws: JavaWebSocketClient[F, G, Ctx]): F[Unit] = {
      def isOpen = Sync[F].blocking(ws.isOpen)

      isOpen.tailRecM(
        _.flatMap[Either[F[Boolean], Unit]](
          if (_)
            Sync[F]
              .blocking(doSend(ws, a))
              .map(Right(_))
          else Applicative[F].pure(Left(Temporal[F].sleep(2.seconds) >> isOpen))
        )
      )
    }

    (for {
      signal <- Resource.eval(SignallingRef[F, Boolean](false))
      dispatcher <- Dispatcher[G].mapK(provide.liftK)
      ws <- Resource
        .make(Sync[F].delay(new JavaWebSocketClient[F, G, Ctx](uri, None, None, Some(signal), dispatcher, k)))(
          ws => Sync[F].delay(ws.close())
        )
      _ <- Resource.eval(Sync[F].delay(ws.connect()))
      _ <- retryResource(stream.evalMap(sender(_, ws)).interruptWhen(signal).compile.drain)
      _ <- monitorWebsocket[F, G, Ctx](ws)
    } yield ws).use(_ => Async[F].never[Unit]).background
  }

  def sendString[F[_]: Async, G[_]: Async, Ctx](
    uri: URI,
    stringStream: Stream[F, String],
    k: Kleisli[Resource[G, *], String, Ctx],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Outcome[F, Throwable, Unit]]] =
    send[F, G, String, Ctx](uri, stringStream, (ws, a) => ws.send(a), k)

  def sendBytes[F[_]: Async, G[_]: Async, Ctx](
    uri: URI,
    byteStream: Stream[F, ByteBuffer],
    k: Kleisli[Resource[G, *], String, Ctx],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Outcome[F, Throwable, Unit]]] =
    send[F, G, ByteBuffer, Ctx](uri, byteStream, (ws, a) => ws.send(a), k)
}
