package io.janstenpickle.controller.coordinator.environment

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.config.CirceMacroConfigSource
import io.janstenpickle.controller.activity.config.{CirceActivityConfigSource, CirceCurrentActivityConfigSource}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe._
import io.janstenpickle.controller.coordinator.config.Configuration.ConfigData
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.{ActivityUpdateEvent, ConfigEvent, SwitchEvent}
import io.janstenpickle.controller.model.{
  Activity,
  Button,
  Command,
  MultiSwitch,
  Remote,
  RemoteSwitchKey,
  Room,
  State,
  VirtualSwitch
}
import io.janstenpickle.controller.remote.config.CirceRemoteConfigSource
import io.janstenpickle.controller.schedule.cron.CirceScheduleConfigSource
import io.janstenpickle.controller.schedule.model.Schedule
import io.janstenpickle.switches.config.{
  CirceMultiSwitchConfigSource,
  CirceSwitchStateConfigSource,
  CirceVirtualSwitchConfigSource
}
import io.janstenpickle.trace4cats.inject.Trace

object ConfigSources {

  def create[F[_]: Concurrent: ContextShift: Timer: Parallel: Trace, G[_]: Concurrent: Timer](
    config: ConfigData,
    configEventPublisher: EventPublisher[F, ConfigEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    activityEventPublisher: EventPublisher[F, ActivityUpdateEvent],
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
      WritableConfigSource[F, NonEmptyString, NonEmptyList[Command]],
      WritableConfigSource[F, RemoteSwitchKey, State]
    )
  ] = {
    def fileSource(name: String) =
      ConfigFileSource
        .polling[F, G](config.dir.resolve(name), config.polling.pollInterval, blocker, config.writeTimeout)

    Parallel
      .parMap9(
        fileSource("activity").flatMap(CirceActivityConfigSource[F, G](_, config.polling, configEventPublisher)),
        fileSource("button").flatMap(CirceButtonConfigSource[F, G](_, config.polling, configEventPublisher)),
        fileSource("remote").flatMap(CirceRemoteConfigSource[F, G](_, config.polling, configEventPublisher)),
        fileSource("virtual-switch")
          .flatMap(CirceVirtualSwitchConfigSource[F, G](_, config.polling, configEventPublisher, switchEventPublisher)),
        fileSource("multi-switch")
          .flatMap(CirceMultiSwitchConfigSource[F, G](_, config.polling, configEventPublisher)),
        fileSource("current-activity")
          .flatMap(CirceCurrentActivityConfigSource[F, G](_, config.polling, activityEventPublisher)),
        fileSource("schedule").flatMap(CirceScheduleConfigSource[F, G](_, config.polling)),
        fileSource("macro").flatMap(CirceMacroConfigSource[F, G](_, config.polling, configEventPublisher)),
        fileSource("switch-state")
          .flatMap(CirceSwitchStateConfigSource[F, G](_, config.polling, switchEventPublisher.narrow))
      )(Tuple9.apply)

  }

}
