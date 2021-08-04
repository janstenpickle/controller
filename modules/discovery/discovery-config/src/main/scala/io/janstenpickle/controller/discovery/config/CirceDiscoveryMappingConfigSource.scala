package io.janstenpickle.controller.discovery.config

import cats.effect.kernel.Async
import cats.effect.{Resource, Sync}
import io.circe.{KeyDecoder, KeyEncoder}
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.circe.{CirceConfigSource, Events}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.DeviceDiscoveryEvent
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue, KeySeparator}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

object CirceDiscoveryMappingConfigSource {
  implicit val keyDecoder: KeyDecoder[DiscoveredDeviceKey] = KeyDecoder { value =>
    value.split(KeySeparator).toList match {
      case deviceId :: deviceType :: Nil => Some(DiscoveredDeviceKey(deviceId, deviceType))
      case _ => None
    }
  }

  implicit val keyEncoder: KeyEncoder[DiscoveredDeviceKey] = KeyEncoder.encodeKeyString.contramap { dk =>
    s"${dk.deviceId}$KeySeparator${dk.deviceType}"
  }

  def apply[F[_]: Sync: Trace, G[_]: Async](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(
    implicit provide: Provide[G, F, Span[G]]
  ): Resource[F, WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue]] =
    CirceConfigSource.polling[F, G, DiscoveredDeviceKey, DiscoveredDeviceValue](
      "discovered.devices",
      pollingConfig,
      config,
      Events.fromDiff(
        discoveryEventPublisher,
        DeviceDiscoveryEvent.DeviceDiscovered,
        (k, _) => DeviceDiscoveryEvent.DeviceRemoved(k)
      ),
      k
    )
}
