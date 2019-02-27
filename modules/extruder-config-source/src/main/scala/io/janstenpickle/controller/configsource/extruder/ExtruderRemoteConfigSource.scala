package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import com.typesafe.config.Config
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import io.janstenpickle.controller.model.Remotes
import extruder.typesafe.instances._
import extruder.circe.yaml.instances._
import extruder.core.{DecoderT, Settings}
import extruder.refined._
import io.circe.Json
import io.janstenpickle.controller.configsource.RemoteConfigSource
import io.janstenpickle.controller.poller.Empty

import scala.concurrent.duration._

object ExtruderRemoteConfigSource {
  implicit val empty: Empty[Remotes] = Empty(Remotes(List.empty, None))

  case class PollingConfig(pollInterval: FiniteDuration = 10.seconds)

  def apply[F[_]: Sync](config: ConfigFileSource[F]): RemoteConfigSource[F] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: DecoderT[EV, ((Settings, CirceSettings), CirceSettings), Remotes, ((Config, Json), Json)] =
      DecoderT[EV, ((Settings, CirceSettings), CirceSettings), Remotes, ((Config, Json), Json)]

    val source = ExtruderConfigSource[F, Remotes](
      config,
      (current, error) => current.copy(error = error.map(_.getMessage)),
      (current, errors) => current.copy(error = Some(errors.map(_.message).toList.mkString(","))),
    )(Sync[F], decoder, empty)

    new RemoteConfigSource[F] {
      override def getRemotes: F[Remotes] = source()
    }
  }

  def polling[F[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig
  ): Resource[F, RemoteConfigSource[F]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: DecoderT[EV, ((Settings, CirceSettings), CirceSettings), Remotes, ((Config, Json), Json)] =
      DecoderT[EV, ((Settings, CirceSettings), CirceSettings), Remotes, ((Config, Json), Json)]

    ExtruderConfigSource
      .polling[F, Remotes](
        pollingConfig.pollInterval,
        config,
        (current, error) => current.copy(error = error.map(_.getMessage)),
        (current, errors) => current.copy(error = Some(errors.map(_.message).toList.mkString(",")))
      )(Timer[F], empty, Concurrent[F], decoder)
      .map { source =>
        new RemoteConfigSource[F] {
          override def getRemotes: F[Remotes] = source()
        }
      }
  }
}
