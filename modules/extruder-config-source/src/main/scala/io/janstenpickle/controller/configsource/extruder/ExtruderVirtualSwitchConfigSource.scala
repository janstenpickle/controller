package io.janstenpickle.controller.configsource.extruder

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.string._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Encoder, Parser, Settings, Show}
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.circe.Json
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.{State, SwitchKey, VirtualSwitch}
import natchez.Trace

object ExtruderVirtualSwitchConfigSource {
  implicit val switchKeyParser: Parser[SwitchKey] = Parser[String].flatMapResult { value =>
    value.split(KeySeparator).toList match {
      case remote :: device :: name :: Nil =>
        for {
          r <- refineV[NonEmpty](remote)
          d <- refineV[NonEmpty](device)
          n <- refineV[NonEmpty](name)
        } yield SwitchKey(r, d, n)
      case _ => Left(s"Invalid remote command value '$value'")
    }
  }
  implicit val switchKeyShow: Show[SwitchKey] = Show { sk =>
    s"${sk.remote}$KeySeparator${sk.device}$KeySeparator${sk.name}"
  }

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: ConfigResult[SwitchKey, VirtualSwitch] => F[Unit]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, SwitchKey, VirtualSwitch]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[SwitchKey, VirtualSwitch], TConfig] =
      Decoder[EV, Settings, ConfigResult[SwitchKey, VirtualSwitch], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[SwitchKey, VirtualSwitch], Config] =
      Encoder[F, Settings, ConfigResult[SwitchKey, VirtualSwitch], Config]

    ExtruderConfigSource
      .polling[F, G, SwitchKey, VirtualSwitch]("virtualSwitches", pollingConfig, config, onUpdate, decoder, encoder)
  }
}
