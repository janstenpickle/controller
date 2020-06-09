package io.janstenpickle.controller.sonos

import cats.instances.list._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Applicative, MonadError, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model
import io.janstenpickle.controller.model.event.RemoteEvent
import io.janstenpickle.controller.model.{RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors}
import natchez.Trace

object SonosRemoteControl {
  def apply[F[_]: Parallel](
    remote: NonEmptyString,
    combinedDeviceName: NonEmptyString,
    discovery: SonosDiscovery[F],
    eventPublisher: EventPublisher[F, RemoteEvent]
  )(implicit F: MonadError[F, Throwable], errors: RemoteControlErrors[F], trace: Trace[F]): F[RemoteControl[F]] =
    RemoteControl.evented(
      RemoteControl
        .traced(
          new RemoteControl[F] {
            private val combinedDevice: SimpleSonosDevice[F] = new SimpleSonosDevice[F] {
              override def applicative: Applicative[F] = Applicative[F]

              private def doOnAll(command: SonosDevice[F] => F[Unit]): F[Unit] =
                discovery.devices.flatMap(_.devices.values.toList.parTraverse_(command))

              private def doOnControllers(command: SonosDevice[F] => F[Unit]): F[Unit] =
                discovery.devices.flatMap(_.devices.values.toList.parTraverse_ { device =>
                  device.isController.flatMap(if (_) command(device) else F.unit)
                })

              def span[A](n: String)(k: F[A]): F[A] = trace.span(s"sonos.combined.$n") {
                trace.put("device.name" -> combinedDeviceName.value) *> k
              }

              override def name: NonEmptyString = combinedDeviceName
              override def play: F[Unit] = span("Play") { doOnControllers(_.play) }
              override def pause: F[Unit] = span("Pause") { doOnControllers(_.pause) }
              override def playPause: F[Unit] = span("PlayPause") { doOnControllers(_.playPause) }
              override def volumeUp: F[Unit] = span("VolumeUp") { doOnAll(_.volumeUp) }
              override def volumeDown: F[Unit] = span("VolumeDown") { doOnAll(_.volumeDown) }
              override def mute: F[Unit] = span("Mute") { doOnAll(_.mute) }
              override def unMute: F[Unit] = span("UnMute") { doOnAll(_.unMute) }
              override def next: F[Unit] = span("Next") { doOnControllers(_.next) }
              override def previous: F[Unit] = span("Previous") { doOnControllers(_.previous) }
            }

            def devices: F[Map[NonEmptyString, SimpleSonosDevice[F]]] = trace.span("sonos.list.devices") {
              discovery.devices.map(_.devices.updated(combinedDeviceName, combinedDevice)).flatTap { devices =>
                trace.put("device.count" -> devices.size)
              }
            }

            private val commands: Map[NonEmptyString, SimpleSonosDevice[F] => F[Unit]] =
              Map(
                (NonEmptyString("play"), _.play),
                (NonEmptyString("pause"), _.pause),
                (Commands.PlayPause, _.playPause),
                (Commands.VolUp, _.volumeUp),
                (Commands.VolDown, _.volumeDown),
                (Commands.Next, _.next),
                (Commands.Previous, _.previous)
              )

            override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
              trace.put("error" -> true, "reason" -> "learning not supported") *> errors.learningNotSupported(
                remoteName
              )

            override def sendCommand(
              source: Option[RemoteCommandSource],
              deviceName: NonEmptyString,
              name: NonEmptyString
            ): F[Unit] =
              if (source == CommandSource)
                devices.flatMap(_.get(deviceName) match {
                  case None =>
                    trace.put("error" -> true, "reason" -> "device not found") *> errors
                      .commandNotFound(remoteName, deviceName, name)
                  case Some(device) =>
                    device.isController.flatMap { isController =>
                      trace.put("controller" -> isController) *> {
                        commands.get(name) match {
                          case None => errors.commandNotFound(remoteName, deviceName, name)
                          case Some(command) => command(device)
                        }
                      }
                    }
                })
              else errors.commandNotFound(remoteName, deviceName, name)

            override def listCommands: F[List[RemoteCommand]] =
              devices.flatMap(_.toList.parFlatTraverse {
                case (deviceName, device) =>
                  device.isController.flatMap { isController =>
                    trace
                      .put("controller" -> isController)
                      .as(commands.keys.toList.map { command =>
                        model.RemoteCommand(remoteName, CommandSource, deviceName, command)
                      })
                  }
              })

            override def remoteName: NonEmptyString = remote
          },
          "manufacturer" -> "sonos"
        ),
      eventPublisher
    )
}
