package io.janstenpickle.controller.sonos

import cats.effect.{Async, Clock}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.{State, SwitchKey, SwitchMetadata}
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import io.janstepickle.controller.events.switch.EventingSwitch
import natchez.Trace

object SonosSwitchProvider {
  def deviceToSwitches[F[_]: Async: Clock](
    deviceName: NonEmptyString,
    dev: SonosDevice[F],
    eventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  )(implicit trace: Trace[F]): Map[SwitchKey, Switch[F]] = {
    def meta(t: String) =
      SwitchMetadata(manufacturer = Some("Sonos"), room = Some(dev.name.value), id = Some(s"${dev.id}:$t"))

    Map(
      (SwitchKey(deviceName, dev.name), EventingSwitch(TracedSwitch(new Switch[F] {
        override def name: NonEmptyString = dev.name
        override def device: NonEmptyString = deviceName
        override def getState: F[State] = dev.isPlaying.map(if (_) State.On else State.Off)
        override def switchOn: F[Unit] = dev.play
        override def switchOff: F[Unit] = dev.pause
        override def metadata: SwitchMetadata = meta("play_pause")
      }), eventPublisher)),
      (
        SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_group")),
        EventingSwitch(TracedSwitch(new Switch[F] {
          override def name: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_group")
          override def device: NonEmptyString = deviceName
          override def getState: F[State] = dev.isGrouped.map(if (_) State.On else State.Off)
          override def switchOn: F[Unit] = dev.group
          override def switchOff: F[Unit] = dev.unGroup
          override def metadata: SwitchMetadata = meta("group")
        }), eventPublisher)
      ),
      (
        SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_mute")),
        EventingSwitch(TracedSwitch(new Switch[F] {
          override def name: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_mute")
          override def device: NonEmptyString = deviceName
          override def getState: F[State] = dev.isMuted.map(if (_) State.On else State.Off)
          override def switchOn: F[Unit] = dev.mute
          override def switchOff: F[Unit] = dev.unMute
          override def metadata: SwitchMetadata = meta("mute")
        }), eventPublisher)
      )
    )
  }

  def apply[F[_]: Async: Clock](
    deviceName: NonEmptyString,
    discovery: SonosDiscovery[F],
    eventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  )(implicit trace: Trace[F]): SwitchProvider[F] =
    new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] = trace.span("sonos.get.switches") {
        discovery.devices.map(_.devices.flatMap { case (_, dev) => deviceToSwitches(deviceName, dev, eventPublisher) })
      }
    }
}
