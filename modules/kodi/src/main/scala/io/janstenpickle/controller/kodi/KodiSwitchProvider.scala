package io.janstenpickle.controller.kodi

import cats.FlatMap
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import natchez.Trace

object KodiSwitchProvider {
  def apply[F[_]: FlatMap: Trace](deviceName: NonEmptyString, discovery: KodiDiscovery[F]): SwitchProvider[F] =
    new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
        discovery.devices.map(_.devices.flatMap {
          case (_, dev) =>
            Map(
              SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_playpause")) ->
                TracedSwitch(new Switch[F] {
                  override def name: NonEmptyString = dev.name
                  override def device: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_playpause")
                  override def getState: F[State] = dev.isPlaying.map(if (_) State.On else State.Off)
                  override def switchOn: F[Unit] = dev.setPlaying(true)
                  override def switchOff: F[Unit] = dev.setPlaying(false)
                }, "manufacturer" -> "kodi"),
              SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_mute")) ->
                TracedSwitch(new Switch[F] {
                  override def name: NonEmptyString = dev.name
                  override def device: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_mute")
                  override def getState: F[State] = dev.isMuted.map(if (_) State.On else State.Off)
                  override def switchOn: F[Unit] = dev.setMuted(true)
                  override def switchOff: F[Unit] = dev.setMuted(false)
                }, "manufacturer" -> "kodi")
            )
        })
    }
}
