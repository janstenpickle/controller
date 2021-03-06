package io.janstenpickle.controller.allinone.environment

import cats.data.NonEmptyList
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.{Applicative, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.config.CirceMacroConfigSource
import io.janstenpickle.controller.activity.config.{CirceActivityConfigSource, CirceCurrentActivityConfigSource}
import io.janstenpickle.controller.allinone.config.Configuration.ConfigData
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe._
import io.janstenpickle.controller.deconz.config.{ActionMapping, CirceButtonMappingConfigSource}
import io.janstenpickle.controller.discovery.config.CirceDiscoveryMappingConfigSource
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.{ActivityUpdateEvent, ConfigEvent, DeviceDiscoveryEvent, SwitchEvent}
import io.janstenpickle.controller.model.{
  Activity,
  Button,
  Command,
  CommandPayload,
  DiscoveredDeviceKey,
  DiscoveredDeviceValue,
  MultiSwitch,
  Remote,
  RemoteCommandKey,
  RemoteSwitchKey,
  Room,
  State,
  VirtualSwitch
}
import io.janstenpickle.controller.remote.config.{CirceRemoteCommandConfigSource, CirceRemoteConfigSource}
import io.janstenpickle.controller.schedule.cron.CirceScheduleConfigSource
import io.janstenpickle.controller.schedule.model.Schedule
import io.janstenpickle.switches.config.{
  CirceMultiSwitchConfigSource,
  CirceSwitchStateConfigSource,
  CirceVirtualSwitchConfigSource
}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

object ConfigSources {

  def create[F[_]: Concurrent: ContextShift: Timer: Parallel: Trace, G[_]: Concurrent: Timer](
    config: ConfigData,
    configEventPublisher: EventPublisher[F, ConfigEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    activityEventPublisher: EventPublisher[F, ActivityUpdateEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent],
    blocker: Blocker,
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[
    F,
    (
      WritableConfigSource[F, String, Activity],
      WritableConfigSource[F, String, Button],
      WritableConfigSource[F, NonEmptyString, Remote],
      WritableConfigSource[F, RemoteSwitchKey, VirtualSwitch],
      WritableConfigSource[F, NonEmptyString, MultiSwitch],
      WritableConfigSource[F, Room, NonEmptyString],
      WritableConfigSource[F, String, Schedule],
      WritableConfigSource[F, String, Set[ActionMapping]],
      WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
      WritableConfigSource[F, NonEmptyString, NonEmptyList[Command]],
      WritableConfigSource[F, RemoteCommandKey, CommandPayload],
      WritableConfigSource[F, RemoteSwitchKey, State]
    )
  ] = {
    def fileSource(name: String) =
      ConfigFileSource
        .polling[F, G](config.dir.resolve(name), config.polling.pollInterval, blocker, config.writeTimeout, k)

    Parallel
      .parMap12[Resource[F, *], WritableConfigSource[F, String, Activity], WritableConfigSource[F, String, Button], WritableConfigSource[
        F,
        NonEmptyString,
        Remote
      ], WritableConfigSource[F, RemoteSwitchKey, VirtualSwitch], WritableConfigSource[F, NonEmptyString, MultiSwitch], WritableConfigSource[
        F,
        Room,
        NonEmptyString
      ], WritableConfigSource[F, String, Schedule], WritableConfigSource[F, String, Set[ActionMapping]], WritableConfigSource[
        F,
        DiscoveredDeviceKey,
        DiscoveredDeviceValue
      ], WritableConfigSource[F, NonEmptyString, NonEmptyList[Command]], WritableConfigSource[F, RemoteCommandKey, CommandPayload], WritableConfigSource[
        F,
        RemoteSwitchKey,
        State
      ], (WritableConfigSource[F, String, Activity], WritableConfigSource[F, String, Button], WritableConfigSource[F, NonEmptyString, Remote], WritableConfigSource[F, RemoteSwitchKey, VirtualSwitch], WritableConfigSource[F, NonEmptyString, MultiSwitch], WritableConfigSource[F, Room, NonEmptyString], WritableConfigSource[F, String, Schedule], WritableConfigSource[F, String, Set[ActionMapping]], WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue], WritableConfigSource[F, NonEmptyString, NonEmptyList[Command]], WritableConfigSource[F, RemoteCommandKey, CommandPayload], WritableConfigSource[F, RemoteSwitchKey, State])](
        fileSource("activity").flatMap(CirceActivityConfigSource[F, G](_, config.polling, configEventPublisher, k)),
        fileSource("button").flatMap(CirceButtonConfigSource[F, G](_, config.polling, configEventPublisher, k)),
        fileSource("remote").flatMap(CirceRemoteConfigSource[F, G](_, config.polling, configEventPublisher, k)),
        fileSource("virtual-switch")
          .flatMap(
            CirceVirtualSwitchConfigSource[F, G](_, config.polling, configEventPublisher, switchEventPublisher, k)
          ),
        fileSource("multi-switch")
          .flatMap(CirceMultiSwitchConfigSource[F, G](_, config.polling, configEventPublisher, k)),
        fileSource("current-activity")
          .flatMap(CirceCurrentActivityConfigSource[F, G](_, config.polling, activityEventPublisher, k)),
        fileSource("schedule").flatMap(CirceScheduleConfigSource[F, G](_, config.polling, k)),
        fileSource("deconz")
          .flatMap(CirceButtonMappingConfigSource[F, G](_, config.polling, _ => Applicative[F].unit, k)),
        fileSource("discovery-mapping")
          .flatMap(CirceDiscoveryMappingConfigSource[F, G](_, config.polling, discoveryEventPublisher, k)),
        fileSource("macro").flatMap(CirceMacroConfigSource[F, G](_, config.polling, configEventPublisher, k)),
        fileSource("remote-command").flatMap(CirceRemoteCommandConfigSource[F, G](_, config.polling, k)),
        fileSource("switch-state")
          .flatMap(CirceSwitchStateConfigSource[F, G](_, config.polling, switchEventPublisher.narrow, k))
      )(Tuple12.apply)

  }

}
