package io.janstenpickle.controller.remote.config

import cats.Applicative
import cats.effect._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.{CommandPayload, RemoteCommandKey}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

object CirceRemoteCommandConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Async](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, WritableConfigSource[F, RemoteCommandKey, CommandPayload]] =
    CirceConfigSource
      .polling[F, G, RemoteCommandKey, CommandPayload](
        "remoteCommand",
        pollingConfig,
        config,
        _ => Applicative[F].unit,
        k
      )
}
