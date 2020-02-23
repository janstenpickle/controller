package io.janstenpickle.controller.events.commands

import cats.Show
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.show._
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.model.Command.{Remote, Sleep, SwitchOff, SwitchOn, ToggleSwitch}
import io.janstenpickle.controller.model.event.CommandEvent

import scala.concurrent.duration._

object EventCommands {
  implicit val show: Show[Command] = Show.show {
    case Sleep(millis) => s"Sleep for ${millis}ms"
    case ToggleSwitch(device, name) => s"Toggle switch '${name.value}' device '${device.value}'"
    case SwitchOn(device, name) => s"Switch on '${name.value}' device '${device.value}'"
    case SwitchOff(device, name) => s"Switch off '${name.value}' device '${device.value}'"
    case Remote(remote, _, device, command) =>
      s"Execute remote command '${command.value}' on '${device.value}' using '${remote.value}'"
    case Command.Macro(name) => s"Execute macro ${name.value}"
  }

  def apply[F[_]: Concurrent](subscriber: EventSubscriber[F, CommandEvent], context: Context[F], `macro`: Macro[F])(
    implicit timer: Timer[F]
  ): Resource[F, F[Unit]] =
    Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
      def repeatStream(stream: Stream[F, Unit]): F[Unit] =
        stream.compile.drain.handleErrorWith { th =>
          logger.error(th)("Event command stream failed, restarting") *> timer.sleep(15.seconds) *> repeatStream(stream)
        }

      def timeout(fa: F[Unit]) = fa.timeoutTo(10.seconds, logger.error("Timed out executing command"))

      val stream = subscriber.subscribe
        .evalTap {
          case CommandEvent.ContextCommand(room, name) =>
            logger.info(s"Running context command '$name' in room '$room'")
          case CommandEvent.MacroCommand(command) =>
            logger.info(command.show)
        }
        .evalMap {
          case CommandEvent.ContextCommand(room, name) =>
            timeout(context.action(room, name))
              .handleErrorWith(th => logger.warn(th)(s"Failed to execute context action $name in room $room"))
          case CommandEvent.MacroCommand(command) =>
            timeout(`macro`.executeCommand(command))
              .handleErrorWith(th => logger.warn(th)(s"Failed to execute command ${command.show}"))
        }

      repeatStream(stream).background
    }
}
