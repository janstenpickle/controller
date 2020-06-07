package io.janstenpickle.controller.configsource.circe

import cats.data.NonEmptyList
import cats.effect._
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import io.circe.refined._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.controller.model.event.ConfigEvent.{MacroAddedEvent, MacroRemovedEvent}
import natchez.Trace

object CirceMacroConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, NonEmptyString, NonEmptyList[Command]]] =
    CirceConfigSource
      .polling[F, G, NonEmptyString, NonEmptyList[Command]](
        "macros",
        pollingConfig,
        config,
        Events.fromDiff(
          configEventPublisher,
          (name, commands) => MacroAddedEvent(name, commands, eventSource),
          (name, _) => MacroRemovedEvent(name, eventSource)
        )
      )

}
