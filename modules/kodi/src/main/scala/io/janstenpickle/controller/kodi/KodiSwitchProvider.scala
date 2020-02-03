package io.janstenpickle.controller.kodi

import cats.effect.{Async, Clock}
import cats.{FlatMap, MonadError}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.{State, SwitchKey, SwitchMetadata}
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import io.janstepickle.controller.events.switch.EventingSwitch
import natchez.Trace

object KodiSwitchProvider {
  def deviceToSwitches[F[_]: MonadError[*[_], Throwable]: Clock](
    deviceName: NonEmptyString,
    dev: KodiDevice[F],
    eventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  )(implicit trace: Trace[F]): Map[SwitchKey, Switch[F]] = {
    def meta(t: String) =
      SwitchMetadata(room = Some(dev.room.value), manufacturer = Some("Kodi"), id = Some(s"${dev.key.deviceId}:$t"))
    Map(
      SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_playpause")) ->
        EventingSwitch(TracedSwitch(new Switch[F] {
          override def name: NonEmptyString = dev.name
          override def device: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_playpause")
          override def getState: F[State] = dev.isPlaying.map(if (_) State.On else State.Off)
          override def switchOn: F[Unit] = dev.setPlaying(true)
          override def switchOff: F[Unit] = dev.setPlaying(false)
          override def metadata: SwitchMetadata = meta("playpause")
        }), eventPublisher),
      SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_mute")) -> EventingSwitch(
        TracedSwitch(new Switch[F] {
          override def name: NonEmptyString = dev.name
          override def device: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_mute")
          override def getState: F[State] = dev.isMuted.map(if (_) State.On else State.Off)
          override def switchOn: F[Unit] = dev.setMuted(true)
          override def switchOff: F[Unit] = dev.setMuted(false)
          override def metadata: SwitchMetadata = meta("mute")
        }),
        eventPublisher
      )
    )
  }

  def apply[F[_]: MonadError[*[_], Throwable]: Trace: Clock](
    deviceName: NonEmptyString,
    discovery: KodiDiscovery[F],
    eventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  ): SwitchProvider[F] =
    new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
        discovery.devices.map(_.devices.flatMap {
          case (_, dev) => deviceToSwitches(deviceName, dev, eventPublisher)
        })
    }
}
