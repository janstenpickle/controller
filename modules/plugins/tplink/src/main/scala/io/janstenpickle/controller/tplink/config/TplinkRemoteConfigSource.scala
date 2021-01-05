package io.janstenpickle.controller.tplink.config

import cats.{Applicative, Functor, Monad, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.model.Button.{RemoteIcon, RemoteLabel, SwitchIcon}
import io.janstenpickle.controller.model.{Button, Remote}
import io.janstenpickle.controller.tplink.{CommandSource, Commands, TplinkDiscovery}
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.flatMap._
import cats.instances.list._
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.tplink.device.TplinkDevice
import io.janstenpickle.trace4cats.inject.Trace

object TplinkRemoteConfigSource {
  def deviceToRemote[F[_]: Applicative](remoteName: NonEmptyString, device: TplinkDevice[F]): F[Option[Remote]] = {
    def remoteIcon(
      device: NonEmptyString,
      command: NonEmptyString,
      icon: NonEmptyString,
      newRow: Boolean = false,
      colored: Boolean = false
    ) = RemoteIcon(remoteName, CommandSource, device, command, icon, Some(newRow), Some(colored), None, None)

    def switchIcon(
      name: NonEmptyString,
      device: NonEmptyString,
      icon: NonEmptyString,
      isOn: Boolean,
      newRow: Boolean = false
    ): SwitchIcon =
      SwitchIcon(name, device, icon, isOn, Some(newRow), None, None, None)

    def template(
      name: NonEmptyString,
      device: NonEmptyString,
      roomName: NonEmptyString,
      isOn: Boolean,
      dimmable: Boolean
    ): List[Button] = List(
      switchIcon(name, device, NonEmptyString("power_settings_new"), isOn, newRow = true),
      remoteIcon(roomName, Commands.BrightnessUp, NonEmptyString("add")),
      remoteIcon(roomName, Commands.BrightnessDown, NonEmptyString("remove"))
    )

    device match {
      case dev: TplinkDevice.SmartBulb[F] if dev.room.isDefined =>
        dev.getState.map { state =>
          Some(
            Remote(
              dev.roomName,
              dev.name,
              template(dev.name, dev.device, dev.roomName, state.isOn, dev.dimmable),
              Set(dev.roomName),
              dev.room.toList
            )
          )
        }
      case _ => Applicative[F].pure(None)
    }
  }

  def apply[F[_]: Parallel: Trace](remoteName: NonEmptyString, discovery: TplinkDiscovery[F])(
    implicit F: Monad[F]
  ): ConfigSource[F, NonEmptyString, Remote] = {

    TracedConfigSource(
      new ConfigSource[F, NonEmptyString, Remote] {
        override def functor: Functor[F] = F

        override def getConfig: F[ConfigResult[NonEmptyString, Remote]] =
          discovery.devices
            .flatMap(
              _.devices.toList.parFlatTraverse { case (_, dev) => deviceToRemote(remoteName, dev).map(_.toList) }
            )
            .map { remotes =>
              ConfigResult(remotes.map(r => r.name -> r).toMap)
            }

        override def getValue(key: NonEmptyString): F[Option[Remote]] = getConfig.map(_.values.get(key))

      },
      "remote",
      "tplink"
    )
  }
}
