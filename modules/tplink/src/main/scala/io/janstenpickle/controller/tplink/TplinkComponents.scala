package io.janstenpickle.controller.tplink

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.kernel.Monoid
import cats.syntax.semigroup._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.discovery.{DeviceRename, Discovery}
import io.janstenpickle.controller.remotecontrol.{RemoteControlErrors, RemoteControls}
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.tplink.config.{TplinkActivityConfigSource, TplinkRemoteConfigSource}
import io.janstenpickle.controller.tplink.device.TplinkDeviceErrors
import io.janstenpickle.controller.tplink.schedule.TplinkScheduler
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
  ]: Concurrent: Timer](
    config: Config,
    workBlocker: Blocker,
    discoveryBlocker: Blocker,
    onUpdate: () => F[Unit],
    onSwitchUpdate: SwitchKey => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Components[F]] = {
    val emptyComponents = Monoid[Components[F]].empty

    if (config.enabled)
      for {
        staticDiscovery <- TplinkDiscovery
          .static[F, G](config.instances, config.commandTimeout, workBlocker, config.polling, onSwitchUpdate)
        discovery <- if (config.dynamicDiscovery)
          TplinkDiscovery
            .dynamic[F, G](
              config.discoveryPort,
              config.commandTimeout,
              config.discoveryTimeout,
              workBlocker,
              discoveryBlocker,
              config.polling,
              onUpdate,
              onSwitchUpdate
            )
            .map(_ |+| staticDiscovery)
        else Resource.pure[F, TplinkDiscovery[F]](staticDiscovery)

      } yield
        emptyComponents.copy(
          switches = TplinkSwitchProvider(discovery),
          remotes = RemoteControls(TplinkRemoteControl(config.remoteName, discovery)),
          scheduler = TplinkScheduler(discovery),
          activityConfig = TplinkActivityConfigSource(discovery),
          remoteConfig = TplinkRemoteConfigSource(config.remoteName, discovery),
          rename = if (config.dynamicDiscovery) TplinkDeviceRename[F](discovery) else DeviceRename.empty[F]
        )
    else
      Resource.pure[F, Components[F]](emptyComponents)
  }
}
