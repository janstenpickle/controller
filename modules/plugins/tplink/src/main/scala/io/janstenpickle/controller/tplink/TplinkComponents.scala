package io.janstenpickle.controller.tplink

import cats.Parallel
import cats.effect.kernel.Async
import cats.effect.{Concurrent, Resource}
import cats.kernel.Monoid
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.discovery.Discovery
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.remotecontrol.{RemoteControlErrors, RemoteControls}
import io.janstenpickle.controller.tplink.config.{TplinkActivityConfigSource, TplinkRemoteConfigSource}
import io.janstenpickle.controller.tplink.device.TplinkDeviceErrors
import io.janstenpickle.controller.tplink.schedule.TplinkScheduler
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

import scala.concurrent.duration._

object TplinkComponents {
  case class Config(
    polling: Discovery.Polling,
    instances: List[TplinkDiscovery.TplinkInstance] = List.empty,
    remoteName: NonEmptyString = NonEmptyString("tplink"),
    discoveryPort: PortNumber = PortNumber(9999),
    commandTimeout: FiniteDuration = 7.seconds,
    discoveryTimeout: FiniteDuration = 10.seconds,
    enabled: Boolean = false
  )

  def apply[F[_]: Async: Parallel: RemoteControlErrors: TplinkDeviceErrors: PollingSwitchErrors: Trace, G[_]: Async](
    config: Config,
    remoteEventPublisher: EventPublisher[F, RemoteEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    configEventPublisher: EventPublisher[F, ConfigEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, Components[F]] = {
    val emptyComponents = Monoid[Components[F]].empty

    if (config.enabled)
      TplinkDiscovery
        .dynamic[F, G](
          config,
          remoteEventPublisher,
          switchEventPublisher,
          configEventPublisher,
          discoveryEventPublisher,
          k
        )
        .flatMap { discovery =>
          Resource.eval(TplinkRemoteControl(config.remoteName, discovery, remoteEventPublisher)).map { remote =>
            emptyComponents.copy(
              switches = TplinkSwitchProvider[F](discovery, switchEventPublisher.narrow),
              remotes = RemoteControls(remote),
              scheduler = TplinkScheduler(discovery),
              activityConfig = TplinkActivityConfigSource(discovery),
              remoteConfig = TplinkRemoteConfigSource(config.remoteName, discovery),
              rename = TplinkDeviceRename[F](discovery, discoveryEventPublisher)
            )
          }
        } else
      Resource.pure[F, Components[F]](emptyComponents)
  }
}
