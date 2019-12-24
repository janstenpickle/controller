package io.janstenpickle.controller.kodi

import cats.FlatMap
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Metadata, Switch, SwitchProvider}
import natchez.Trace

object KodiSwitchProvider {
  def apply[F[_]: FlatMap: Trace](deviceName: NonEmptyString, discovery: KodiDiscovery[F]): SwitchProvider[F] =
    new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
        discovery.devices.map(_.devices.flatMap {
          case (_, dev) =>
            def meta(index: Int) =
              Metadata(
                room = Some(dev.room.value),
                manufacturer = Some("Kodi"),
                id = Some(s"${dev.key.deviceId}:$index")
              )
            Map(
              SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_playpause")) ->
                TracedSwitch(new Switch[F] {
                  override def name: NonEmptyString = dev.name
                  override def device: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_playpause")
                  override def getState: F[State] = dev.isPlaying.map(if (_) State.On else State.Off)
                  override def switchOn: F[Unit] = dev.setPlaying(true)
                  override def switchOff: F[Unit] = dev.setPlaying(false)
                  override def metadata: Metadata = meta(0)
                }),
              SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_mute")) ->
                TracedSwitch(new Switch[F] {
                  override def name: NonEmptyString = dev.name
                  override def device: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_mute")
                  override def getState: F[State] = dev.isMuted.map(if (_) State.On else State.Off)
                  override def switchOn: F[Unit] = dev.setMuted(true)
                  override def switchOff: F[Unit] = dev.setMuted(false)
                  override def metadata: Metadata = meta(1)
                })
            )
        })
    }
}
