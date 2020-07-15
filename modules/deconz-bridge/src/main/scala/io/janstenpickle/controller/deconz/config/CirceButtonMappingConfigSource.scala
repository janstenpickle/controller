package io.janstenpickle.controller.deconz.config

import cats.effect._
import cats.instances.set._
import cats.instances.string._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.{Diff, PollingConfig}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.trace4cats.inject.Trace

object CirceButtonMappingConfigSource {

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: Diff[String, Set[ActionMapping]] => F[Unit]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, String, Set[ActionMapping]]] =
    CirceConfigSource
      .polling[F, G, String, Set[ActionMapping]]("mapping", pollingConfig, config, onUpdate)
}
