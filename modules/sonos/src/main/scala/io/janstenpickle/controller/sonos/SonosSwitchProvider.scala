package io.janstenpickle.controller.sonos

import cats.effect.Async
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Metadata, Switch, SwitchProvider}
import natchez.Trace

object SonosSwitchProvider {
  def apply[F[_]: Async](deviceName: NonEmptyString, discovery: SonosDiscovery[F])(
    implicit trace: Trace[F]
  ): SwitchProvider[F] =
    new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] = trace.span("sonos.get.switches") {
        discovery.devices.map(_.devices.flatMap {
          case (_, dev) =>
            def meta(index: Int) =
              Metadata(manufacturer = Some("Sonos"), id = Some(s"${dev.id}:$index"))

            Map((SwitchKey(deviceName, dev.name), TracedSwitch(new Switch[F] {
              override def name: NonEmptyString = dev.name
              override def device: NonEmptyString = deviceName
              override def getState: F[State] = dev.isPlaying.map(if (_) State.On else State.Off)
              override def switchOn: F[Unit] = dev.play
              override def switchOff: F[Unit] = dev.pause
              override def metadata: Metadata = meta(0)
            })), (SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_group")), TracedSwitch(new Switch[F] {
              override def name: NonEmptyString = dev.name
              override def device: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_group")
              override def getState: F[State] = dev.isGrouped.map(if (_) State.On else State.Off)
              override def switchOn: F[Unit] = dev.group
              override def switchOff: F[Unit] = dev.unGroup
              override def metadata: Metadata = meta(1)
            })), (SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_mute")), TracedSwitch(new Switch[F] {
              override def name: NonEmptyString = dev.name
              override def device: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_mute")
              override def getState: F[State] = dev.isMuted.map(if (_) State.On else State.Off)
              override def switchOn: F[Unit] = dev.mute
              override def switchOff: F[Unit] = dev.unMute
              override def metadata: Metadata = meta(2)
            })))
        })
      }
    }
}
