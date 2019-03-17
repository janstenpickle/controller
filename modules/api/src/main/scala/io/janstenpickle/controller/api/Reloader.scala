package io.janstenpickle.controller.api

import cats.effect.{Concurrent, ExitCode, Timer}
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object Reloader {
  type ReloadSignal[F[_]] = SignallingRef[F, Boolean]
  type ExitSignal[F[_]] = SignallingRef[F, Boolean]

  private val log = LoggerFactory.getLogger(getClass)

  def apply[F[_]](
    makeStream: (ReloadSignal[F], ExitSignal[F]) => Stream[F, ExitCode]
  )(implicit F: Concurrent[F], timer: Timer[F]): Stream[F, ExitCode] = {
    def repeatStream(reload: ReloadSignal[F], signal: ExitSignal[F]): Stream[F, ExitCode] =
      makeStream(reload, signal).handleErrorWith(
        th =>
          Stream[F, Unit](log.error("Failed to start Controller", th))
            .evalMap(_ => timer.sleep(10.seconds))
            .flatMap(_ => repeatStream(reload, signal))
      )

    def processSignal(
      reload: ReloadSignal[F],
      signal: ExitSignal[F],
      stop: SignallingRef[F, Boolean]
    ): Stream[F, Unit] =
      reload.discrete.evalMap { doReload =>
        if (doReload) signal.set(true) *> signal.set(false) *> reload.set(false)
        else
          signal.get.flatMap { doExit =>
            if (doExit) stop.set(true)
            else ().pure
          }
      }

    for {
      reload <- Stream.eval(SignallingRef[F, Boolean](false))
      signal <- Stream.eval(SignallingRef[F, Boolean](false))
      stop <- Stream.eval(SignallingRef[F, Boolean](false))
      code <- repeatStream(reload, signal).concurrently(processSignal(reload, signal, stop)).interruptWhen(stop)
    } yield code
  }

}
