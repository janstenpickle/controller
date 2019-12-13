package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import com.typesafe.config.{Config => TConfig}
import extruder.cats.effect.EffectValidation
import extruder.core._
import extruder.refined._
import extruder.typesafe.instances._
import extruder.typesafe.IntermediateTypes.Config
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import natchez.Trace

object ExtruderDiscoveryMappingConfigSource {
  implicit val keyParser: Parser[DiscoveredDeviceKey] = Parser[String].flatMapResult { value =>
    value.split(KeySeparator).toList match {
      case deviceId :: deviceType :: Nil => Right(DiscoveredDeviceKey(deviceId, deviceType))
      case _ => Left(s"Invalid discovered device key '$value'")
    }
  }

  implicit val keyShow: Show[DiscoveredDeviceKey] = Show[String].contramap { dk =>
    s"${dk.deviceId}$KeySeparator${dk.deviceType}"
  }

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: ConfigResult[DiscoveredDeviceKey, DiscoveredDeviceValue] => F[Unit]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[DiscoveredDeviceKey, DiscoveredDeviceValue], TConfig] =
      Decoder[EV, Settings, ConfigResult[DiscoveredDeviceKey, DiscoveredDeviceValue], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[DiscoveredDeviceKey, DiscoveredDeviceValue], Config] =
      Encoder[F, Settings, ConfigResult[DiscoveredDeviceKey, DiscoveredDeviceValue], Config]

    ExtruderConfigSource
      .polling[F, G, DiscoveredDeviceKey, DiscoveredDeviceValue](
        "discovered.devices",
        pollingConfig,
        config,
        onUpdate,
        decoder,
        encoder
      )
  }
}
