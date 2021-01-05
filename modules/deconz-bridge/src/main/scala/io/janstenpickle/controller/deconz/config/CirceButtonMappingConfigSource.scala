package io.janstenpickle.controller.deconz.config

import cats.effect._
import cats.instances.set._
import cats.instances.string._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.{Diff, PollingConfig}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

object CirceButtonMappingConfigSource {

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: Diff[String, Set[ActionMapping]] => F[Unit],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, WritableConfigSource[F, String, Set[ActionMapping]]] =
    CirceConfigSource
      .polling[F, G, String, Set[ActionMapping]]("mapping", pollingConfig, config, onUpdate, k)
}
