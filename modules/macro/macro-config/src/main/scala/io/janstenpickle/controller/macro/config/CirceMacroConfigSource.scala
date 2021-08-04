package io.janstenpickle.controller.`macro`.config

import cats.data.NonEmptyList
import cats.effect._
import cats.instances.string._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.refined._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.circe.{eventSource, CirceConfigSource, Events}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.controller.model.event.ConfigEvent.{MacroAddedEvent, MacroRemovedEvent}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

object CirceMacroConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Async](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(
    implicit provide: Provide[G, F, Span[G]]
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
        ),
        k
      )

}
