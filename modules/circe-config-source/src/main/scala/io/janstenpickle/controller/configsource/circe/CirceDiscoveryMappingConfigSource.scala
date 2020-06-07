package io.janstenpickle.controller.configsource.circe

import cats.effect.{Concurrent, Resource, Sync, Timer}
import io.circe.{KeyDecoder, KeyEncoder}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import io.janstenpickle.controller.model.event.DeviceDiscoveryEvent
import natchez.Trace
import io.janstenpickle.controller.model.KeySeparator

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

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue]] =
    CirceConfigSource.polling[F, G, DiscoveredDeviceKey, DiscoveredDeviceValue](
      "discovered.devices",
      pollingConfig,
      config,
      Events.fromDiff(
        discoveryEventPublisher,
        DeviceDiscoveryEvent.DeviceDiscovered,
        (k, _) => DeviceDiscoveryEvent.DeviceRemoved(k)
      )
    )
}
