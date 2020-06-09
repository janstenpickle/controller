package io.janstenpickle.switches.config

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import io.circe.refined._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.circe.{eventSource, CirceConfigSource, Events}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.MultiSwitch
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.controller.model.event.ConfigEvent.{MultiSwitchAddedEvent, MultiSwitchRemovedEvent}
import natchez.Trace

object CirceMultiSwitchConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, NonEmptyString, MultiSwitch]] =
    CirceConfigSource
      .polling[F, G, NonEmptyString, MultiSwitch](
        "multiSwitches",
        pollingConfig,
        config,
        Events.fromDiffValues(
          configEventPublisher,
          MultiSwitchAddedEvent(_, eventSource),
          m => MultiSwitchRemovedEvent(m.name, eventSource)
        )
      )
}
