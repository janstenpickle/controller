package io.janstenpickle.controller.configsource.circe

import cats.Applicative
import cats.effect._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.{CommandPayload, RemoteCommandKey}
import natchez.Trace

object CirceRemoteCommandConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](config: ConfigFileSource[F], pollingConfig: PollingConfig)(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, RemoteCommandKey, CommandPayload]] =
    CirceConfigSource
      .polling[F, G, RemoteCommandKey, CommandPayload]("remoteCommand", pollingConfig, config, _ => Applicative[F].unit)
}
