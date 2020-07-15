package io.janstenpickle.controller.events.commands

import cats.Show
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.functor._
import cats.syntax.show._
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.errors.ErrorHandler
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.model.Command.{Remote, Sleep, SwitchOff, SwitchOn, ToggleSwitch}
import io.janstenpickle.controller.model.event.CommandEvent
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.events.syntax.stream._
import io.janstenpickle.trace4cats.inject.Trace

import scala.concurrent.duration._

object EventCommands {
  implicit val show: Show[Command] = Show.show {
    case Sleep(millis) => s"Sleep for ${millis}ms"
    case ToggleSwitch(device, name) => s"Toggle switch '${name.value}' device '${device.value}'"
    case SwitchOn(device, name) => s"Switch on '${name.value}' device '${device.value}'"
    case SwitchOff(device, name) => s"Switch off '${name.value}' device '${device.value}'"
    case Remote(remote, source, device, command) =>
      s"Execute remote command '${command.value}' on '${device.value}' from source '$source' using '${remote.value}'"
    case Command.Macro(name) => s"Execute macro ${name.value}"
  }

  def apply[F[_]: Concurrent, G[_]](
    subscriber: EventSubscriber[F, CommandEvent],
    context: Context[F],
    `macro`: Macro[F],
    activity: Activity[F],
    remotes: RemoteControls[F],
    devices: DeviceRename[F]
  )(
    implicit timer: Timer[F],
    trace: Trace[F],
    errorHandler: ErrorHandler[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, F[Unit]] =
    Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
      def repeatStream(stream: Stream[F, Unit]): F[Unit] =
        stream.compile.drain.handleErrorWith { th =>
          logger.error(th)("Event command stream failed, restarting") *> timer.sleep(15.seconds) *> repeatStream(stream)
        }

      def handleErrors(fa: F[Unit], message: String, timeout: FiniteDuration = 10.seconds): F[Unit] = {
        def log(th: Throwable) = logger.warn(th)(message)

        errorHandler
          .handleWith(fa.timeoutTo(timeout, logger.error("Timed out executing command")).handleErrorWith(log))(log)
      }

      val stream = subscriber.subscribeEvent
        .evalTap(_.value match {
          case CommandEvent.ContextCommand(room, name) =>
            logger.info(s"Running context command '$name' in room '$room'")
          case CommandEvent.ActivityCommand(room, name) =>
            logger.info(s"Setting activity '$name' in room '$room'")
          case CommandEvent.MacroCommand(command) =>
            logger.info(command.show)
          case CommandEvent.RemoteLearnCommand(remote, device, name) =>
            logger.info(s"Learning command '$name' on remote '$remote' for device '$device'")
          case CommandEvent.RenameDeviceCommand(key, value) =>
            logger.info(s"Rename device with key '$key' to '$value''")
        })
        .evalMapTrace("run.command") {
          case CommandEvent.ContextCommand(room, name) =>
            handleErrors(context.action(room, name), s"Failed to execute context action $name in room $room")
          case CommandEvent.ActivityCommand(room, name) =>
            handleErrors(activity.setActivity(room, name), s"Failed to set activity $name in room $room")
          case CommandEvent.MacroCommand(command) =>
            handleErrors(`macro`.executeCommand(command), s"Failed to execute command ${command.show}")
          case CommandEvent.RemoteLearnCommand(remote, device, name) =>
            handleErrors(
              remotes.learn(remote, device, name),
              s"Failed to learn command '$name' on remote '$remote' for device '$device'"
            )
          case CommandEvent.RenameDeviceCommand(key, value) =>
            handleErrors(devices.rename(key, value).void, s"Failed to rename device '$key' to '$value'", 2.minutes)
        }

      repeatStream(stream).background
    }
}
