package io.janstenpickle.controller.kodi

import cats.Monad
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.kodi.Commands._
import io.janstenpickle.controller.model.{RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors}
import natchez.Trace

object KodiRemoteControl {
  final val RemoteName = NonEmptyString("kodi")
  final val CommandSource = Some(RemoteCommandSource(NonEmptyString("kodi"), NonEmptyString("programatic")))

  def apply[F[_]: Trace](
    discovery: KodiDiscovery[F]
  )(implicit F: Monad[F], errors: RemoteControlErrors[F]): RemoteControl[F] =
    RemoteControl
      .traced(
        new RemoteControl[F] {
          override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
            errors.learningNotSupported(RemoteName)

          private val commands: Map[NonEmptyString, KodiDevice[F] => F[Unit]] =
            Map[NonEmptyString, KodiDevice[F] => F[Unit]](
              ScanVideoLibrary -> (_.scanVideoLibrary),
              PlayPause -> (dev => dev.isPlaying.flatMap(p => dev.setPlaying(!p))),
              VolUp -> (_.volumeUp),
              VolDown -> (_.volumeDown)
            ) ++ InputCommands.map { cmd =>
              cmd -> ((device: KodiDevice[F]) => device.sendInputAction(cmd))
            }.toMap

          override def sendCommand(
            source: Option[RemoteCommandSource],
            device: NonEmptyString,
            name: NonEmptyString
          ): F[Unit] =
            discovery.devices.flatMap { devices =>
              (devices.get(device), commands.get(name)) match {
                case (Some(client), Some(command)) => command(client)
                case _ => errors.commandNotFound(RemoteName, device, name)
              }
            }

          override lazy val listCommands: F[List[RemoteCommand]] =
            discovery.devices.map { devices =>
              (for {
                instance <- devices.keys
                command <- commands.keys
              } yield RemoteCommand(RemoteName, CommandSource, instance, command)).toList
            }
        },
        "type" -> "kodi"
      )
}
