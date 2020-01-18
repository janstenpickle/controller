package io.janstenpickle.controller.api.environment

import cats.data.NonEmptyList
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.{Applicative, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.api.config.Configuration.ConfigData
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.extruder._
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
import io.janstenpickle.controller.schedule.model.Schedule
import io.janstenpickle.deconz.config.{ActionMapping, ExtruderButtonMappingConfigSource}
import natchez.Trace

object ConfigSources {

  def create[F[_]: Concurrent: ContextShift: Timer: Parallel: Trace, G[_]: Concurrent: Timer](
    config: ConfigData,
    configEventPublisher: EventPublisher[F, ConfigEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    activityEventPublisher: EventPublisher[F, ActivityUpdateEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent],
    blocker: Blocker
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[
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
        .polling[F, G](config.dir.resolve(name), config.polling.pollInterval, blocker, config.writeTimeout)

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
        fileSource("activity").flatMap(ExtruderActivityConfigSource[F, G](_, config.polling, configEventPublisher)),
        fileSource("button").flatMap(ExtruderButtonConfigSource[F, G](_, config.polling, configEventPublisher)),
        fileSource("remote").flatMap(ExtruderRemoteConfigSource[F, G](_, config.polling, configEventPublisher)),
        fileSource("virtual-switch").flatMap(
          ExtruderVirtualSwitchConfigSource[F, G](_, config.polling, configEventPublisher, switchEventPublisher)
        ),
        fileSource("multi-switch")
          .flatMap(ExtruderMultiSwitchConfigSource[F, G](_, config.polling, configEventPublisher)),
        fileSource("current-activity")
          .flatMap(ExtruderCurrentActivityConfigSource[F, G](_, config.polling, activityEventPublisher)),
        fileSource("schedule").flatMap(ExtruderScheduleConfigSource[F, G](_, config.polling)),
        fileSource("deconz")
          .flatMap(ExtruderButtonMappingConfigSource[F, G](_, config.polling, _ => Applicative[F].unit)),
        fileSource("discovery-mapping")
          .flatMap(ExtruderDiscoveryMappingConfigSource[F, G](_, config.polling, discoveryEventPublisher)),
        fileSource("macro").flatMap(ExtruderMacroConfigSource[F, G](_, config.polling, configEventPublisher)),
        fileSource("remote-command").flatMap(ExtruderRemoteCommandConfigSource[F, G](_, config.polling)),
        fileSource("switch-state")
          .flatMap(ExtruderSwitchStateConfigSource[F, G](_, config.polling, switchEventPublisher.narrow))
      )(Tuple12.apply)

  }

}
