package io.janstenpickle.controller.tplink

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.kernel.Monoid
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.discovery.{DeviceRename, Discovery}
import io.janstenpickle.controller.remotecontrol.{RemoteControlErrors, RemoteControls}
import io.janstenpickle.controller.tplink.config.{TplinkActivityConfigSource, TplinkRemoteConfigSource}
import io.janstenpickle.controller.tplink.device.TplinkDeviceErrors
import natchez.Trace

import scala.concurrent.duration._

object TplinkComponents {
  case class Config(
    polling: Discovery.Polling,
    instances: List[TplinkDiscovery.TplinkInstance] = List.empty,
    remoteName: NonEmptyString = NonEmptyString("tplink"),
    discoveryPort: PortNumber = PortNumber(9999),
    dynamicDiscovery: Boolean = true,
    commandTimeout: FiniteDuration = 2.seconds,
    discoveryTimeout: FiniteDuration = 5.seconds,
    enabled: Boolean = false
  )

  def apply[F[_]: Concurrent: Timer: ContextShift: Parallel: RemoteControlErrors: TplinkDeviceErrors: PollingSwitchErrors: Trace, G[
    _
  ]: Concurrent: Timer](config: Config, blocker: Blocker, onUpdate: () => F[Unit], onDeviceUpdate: () => F[Unit])(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, Components[F]] = {
    val emptyComponents = Monoid[Components[F]].empty

    if (config.enabled)
      for {
        staticDiscovery <- TplinkDiscovery
          .static[F, G](config.instances, config.commandTimeout, blocker, config.polling, onDeviceUpdate)
        (rename, discovery) <- if (config.dynamicDiscovery)
          TplinkDiscovery
            .dynamic[F, G](
              config.discoveryPort,
              config.commandTimeout,
              config.discoveryTimeout,
              blocker,
              config.polling,
              onUpdate,
              onDeviceUpdate
            )
            .map {
              case (rename, dynamic) =>
                (rename, Discovery.combined(dynamic, staticDiscovery))
            } else Resource.pure[F, (DeviceRename[F], TplinkDiscovery[F])](DeviceRename.empty[F], staticDiscovery)

      } yield
        emptyComponents.copy(
          switches = TplinkSwitchProvider(discovery),
          remotes = RemoteControls(TplinkRemoteControl(config.remoteName, discovery)),
          activityConfig = TplinkActivityConfigSource(discovery),
          remoteConfig = TplinkRemoteConfigSource(config.remoteName, discovery),
          rename = rename
        )
    else
      Resource.pure[F, Components[F]](emptyComponents)
  }
}
