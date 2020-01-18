package io.janstenpickle.controller.api.environment

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.kernel.Monoid
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.api.config.Configuration.Config
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.BroadlinkComponents
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.kodi.{KodiComponents, KodiErrors}
import io.janstenpickle.controller.model.{CommandPayload, DiscoveredDeviceKey, DiscoveredDeviceValue}
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.sonos.SonosComponents
import io.janstenpickle.controller.store.{RemoteCommandStore, SwitchStateStore}
import io.janstenpickle.controller.tplink.TplinkComponents
import io.janstenpickle.controller.tplink.device.TplinkDeviceErrors
import natchez.Trace
import org.http4s.client.Client
import cats.instances.list._

object ComponentsEnv {
  def create[F[_]: Concurrent: Parallel: ContextShift: Timer: PollingSwitchErrors: Trace: RemoteControlErrors: TplinkDeviceErrors: KodiErrors, G[
    _
  ]: Concurrent: Timer](
    config: Config,
    httpClient: Client[F],
    remoteCommandStore: RemoteCommandStore[F, CommandPayload],
    switchStateStore: SwitchStateStore[F],
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    workBlocker: Blocker,
    discoveryBlocker: Blocker,
    remoteEventPublisher: EventPublisher[F, RemoteEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    configEventPublisher: EventPublisher[F, ConfigEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent]
  )(implicit liftLower: ContextualLiftLower[G, F, String]) =
    Parallel
      .parSequence[List, Resource[F, *], Components[F]](
        List(
          BroadlinkComponents[F, G](
            config.broadlink,
            remoteCommandStore,
            switchStateStore,
            nameMapping,
            workBlocker,
            discoveryBlocker,
            remoteEventPublisher,
            switchEventPublisher,
            configEventPublisher,
            discoveryEventPublisher
          ),
          TplinkComponents[F, G](
            config.tplink,
            workBlocker,
            discoveryBlocker,
            remoteEventPublisher,
            switchEventPublisher,
            configEventPublisher,
            discoveryEventPublisher
          ),
          SonosComponents[F, G](
            config.sonos,
            workBlocker,
            discoveryBlocker,
            remoteEventPublisher,
            switchEventPublisher,
            configEventPublisher,
            discoveryEventPublisher
          ),
          KodiComponents[F, G](
            httpClient,
            discoveryBlocker,
            config.kodi,
            nameMapping,
            remoteEventPublisher,
            switchEventPublisher,
            configEventPublisher,
            discoveryEventPublisher
          )
        )
      )
      .map(Monoid[Components[F]].combineAll)
}
