package io.janstenpickle.controller.events.commands

import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.applicativeError._
import cats.syntax.apply._
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.model.event.CommandEvent
import cats.effect.syntax.concurrent._

import scala.concurrent.duration._

object EventCommands {
  def apply[F[_]: Concurrent](eventPubSub: EventPubSub[F, CommandEvent], context: Context[F], `macro`: Macro[F])(
    implicit timer: Timer[F]
  ): Resource[F, Unit] =
    Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
      def repeatStream(stream: Stream[F, Unit]): F[Unit] =
        stream.compile.drain.handleErrorWith { th =>
          logger.error(th)("Stats stream failed, restarting") *> timer.sleep(15.seconds) *> repeatStream(stream)
        }

      def timeout(fa: F[Unit]) = fa.timeoutTo(1.minute, logger.error("Timed out executing command"))

      for {
        subscriber <- eventPubSub.subscriberResource
        stream = subscriber.subscribe.evalMap {
          case CommandEvent.ContextCommand(room, name) => timeout(context.action(room, name))
          case CommandEvent.MacroCommand(command) => timeout(`macro`.executeCommand(command))
        }
        _ <- Resource.make(repeatStream(stream).start)(_.cancel)
      } yield ()
    }
}
