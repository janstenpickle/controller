package io.janstenpickle.controller.sonos

import cats.{Applicative, MonadError}
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors}
import io.janstenpickle.controller.store.RemoteCommand
import cats.syntax.traverse._
import cats.instances.list._

object SonosRemoteControl {
  def apply[F[_]](remoteName: NonEmptyString, combinedDeviceName: NonEmptyString, discovery: SonosDiscovery[F])(
    implicit F: MonadError[F, Throwable],
    errors: RemoteControlErrors[F]
  ): RemoteControl[F] =
    new RemoteControl[F] {
      private val combinedDevice: SimpleSonosDevice[F] = new SimpleSonosDevice[F] {
        override def applicative: Applicative[F] = Applicative[F]

        private def doOnAll(command: SonosDevice[F] => F[Unit]): F[Unit] =
          discovery.devices.flatMap(_.values.toList.traverse(command).void)

        private def doOnControllers(command: SonosDevice[F] => F[Unit]): F[Unit] =
          discovery.devices.flatMap(_.values.toList.traverse { device =>
            device.isController.flatMap(if (_) command(device) else F.unit)
          }.void)

        override def name: NonEmptyString = combinedDeviceName
        override def play: F[Unit] = doOnControllers(_.play)
        override def pause: F[Unit] = doOnControllers(_.pause)
        override def playPause: F[Unit] = doOnControllers(_.playPause)
        override def volumeUp: F[Unit] = doOnAll(_.volumeUp)
        override def volumeDown: F[Unit] = doOnAll(_.volumeDown)
        override def mute: F[Unit] = doOnAll(_.mute)
        override def next: F[Unit] = doOnControllers(_.next)
        override def previous: F[Unit] = doOnControllers(_.previous)
      }

      def devices: F[Map[NonEmptyString, SimpleSonosDevice[F]]] =
        discovery.devices.map(_.updated(combinedDeviceName, combinedDevice))

      private val basicCommands: Map[NonEmptyString, SimpleSonosDevice[F] => F[Unit]] =
        Map(
          (NonEmptyString("play"), _.play),
          (NonEmptyString("pause"), _.pause),
          (Commands.PlayPause, _.playPause),
          (Commands.VolUp, _.volumeUp),
          (Commands.VolDown, _.volumeDown),
          (Commands.Mute, _.mute)
        )

      private val commands: Map[NonEmptyString, SimpleSonosDevice[F] => F[Unit]] =
        basicCommands ++ Map[NonEmptyString, SimpleSonosDevice[F] => F[Unit]](
          (Commands.Next, _.next),
          (Commands.Previous, _.previous)
        )

      override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        errors.learningNotSupported(remoteName)

      override def sendCommand(deviceName: NonEmptyString, name: NonEmptyString): F[Unit] =
        devices.flatMap(_.get(deviceName) match {
          case None => errors.commandNotFound(remoteName, deviceName, name)
          case Some(device) =>
            device.isController.flatMap { isController =>
              (if (isController) commands else basicCommands).get(name) match {
                case None => errors.commandNotFound(remoteName, deviceName, name)
                case Some(command) => command(device)
              }
            }
        })

      override def listCommands: F[List[RemoteCommand]] =
        devices.flatMap(_.toList.flatTraverse {
          case (deviceName, device) =>
            device.isController.map { isController =>
              (if (isController) commands else basicCommands).keys.toList.map { command =>
                RemoteCommand(remoteName, deviceName, command)
              }
            }
        })
    }
}
