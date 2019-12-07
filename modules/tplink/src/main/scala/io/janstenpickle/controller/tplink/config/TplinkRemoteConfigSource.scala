package io.janstenpickle.controller.tplink.config

import cats.{Functor, Monad, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.model.Button.{RemoteIcon, RemoteLabel, SwitchIcon}
import io.janstenpickle.controller.model.{Button, Remote}
import io.janstenpickle.controller.tplink.TplinkDiscovery
import natchez.Trace
import io.janstenpickle.controller.tplink.CommandSource
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.flatMap._
import cats.instances.list._
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.tplink.device.TplinkDevice

object TplinkRemoteConfigSource {
  def apply[F[_]: Parallel: Trace](remoteName: NonEmptyString, discovery: TplinkDiscovery[F])(
    implicit F: Monad[F]
  ): ConfigSource[F, NonEmptyString, Remote] = {

    def remoteIcon(
      device: NonEmptyString,
      command: NonEmptyString,
      icon: NonEmptyString,
      newRow: Boolean = false,
      colored: Boolean = false
    ) = RemoteIcon(remoteName, CommandSource, device, command, icon, Some(newRow), Some(colored), None, None)

    def remoteLabel(
      device: NonEmptyString,
      command: NonEmptyString,
      label: NonEmptyString,
      newRow: Boolean = false,
      colored: Boolean = false
    ) = RemoteLabel(remoteName, CommandSource, device, command, label, Some(newRow), Some(colored), None, None)

    def switchIcon(
      name: NonEmptyString,
      device: NonEmptyString,
      icon: NonEmptyString,
      isOn: Boolean,
      newRow: Boolean = false
    ): SwitchIcon =
      SwitchIcon(name, device, icon, isOn, Some(newRow), None, None, None)

    def template(name: NonEmptyString, device: NonEmptyString, isOn: Boolean): List[Button] = List(
      switchIcon(name, device, NonEmptyString("power_settings_new"), isOn)
    )

    TracedConfigSource(
      new ConfigSource[F, NonEmptyString, Remote] {
        override def functor: Functor[F] = F

        override def getConfig: F[ConfigResult[NonEmptyString, Remote]] =
          discovery.devices
            .flatMap(_.devices.toList.parFlatTraverse {
              case (_, dev: TplinkDevice.SmartBulb[F]) if dev.room.isDefined =>
                dev.getState.map { state =>
                  List(
                    dev.name -> Remote(
                      dev.roomName,
                      dev.name,
                      template(dev.name, dev.device, state.isOn),
                      Set(dev.roomName),
                      dev.room.toList
                    )
                  )
                }
              case _ => F.pure(List.empty[(NonEmptyString, Remote)])
            })
            .map { remotes =>
              ConfigResult(remotes.toMap)
            }

        override def getValue(key: NonEmptyString): F[Option[Remote]] = getConfig.map(_.values.get(key))

      },
      "remote",
      "tplink"
    )
  }
}
