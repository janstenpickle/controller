package io.janstenpickle.controller.configsource.circe

import cats.effect.kernel.Async
import cats.effect.{Resource, Sync}
import cats.instances.string._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Button
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.controller.model.event.ConfigEvent.{ButtonAddedEvent, ButtonRemovedEvent}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

object CirceButtonConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Async](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, WritableConfigSource[F, String, Button]] =
    CirceConfigSource
      .polling[F, G, String, Button](
        "buttons",
        pollingConfig,
        config,
        Events.fromDiffValues(
          configEventPublisher,
          ButtonAddedEvent(_, eventSource),
          b => ButtonRemovedEvent(b.name, b.room, eventSource)
        ),
        k
      )
}
