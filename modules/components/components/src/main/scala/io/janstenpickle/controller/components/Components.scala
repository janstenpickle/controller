package io.janstenpickle.controller.components

import cats.data.NonEmptyList
import cats.derived.semi.monoid
import cats.kernel.Monoid
import cats.{Monad, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.model.{Activity, Button, Command, Remote}
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors, RemoteControls}
import io.janstenpickle.controller.schedule.Scheduler
import io.janstenpickle.controller.switch.SwitchProvider

case class Components[F[_]](
  remotes: RemoteControls[F],
  switches: SwitchProvider[F],
  rename: DeviceRename[F],
  scheduler: Scheduler[F],
  activityConfig: ConfigSource[F, String, Activity],
  remoteConfig: ConfigSource[F, NonEmptyString, Remote],
  macroConfig: ConfigSource[F, NonEmptyString, NonEmptyList[Command]],
  buttonConfig: ConfigSource[F, String, Button],
)

object Components {
  def apply[F[_]: Monad: Parallel: RemoteControlErrors](
    remote: RemoteControl[F],
    switches: SwitchProvider[F],
    rename: DeviceRename[F],
    scheduler: Scheduler[F],
    activityConfig: ConfigSource[F, String, Activity],
    remoteConfig: ConfigSource[F, NonEmptyString, Remote],
    macroConfig: ConfigSource[F, NonEmptyString, NonEmptyList[Command]],
    buttonConfig: ConfigSource[F, String, Button],
  ): Components[F] =
    Components(
      RemoteControls(remote),
      switches: SwitchProvider[F],
      rename: DeviceRename[F],
      scheduler: Scheduler[F],
      activityConfig: ConfigSource[F, String, Activity],
      remoteConfig: ConfigSource[F, NonEmptyString, Remote],
      macroConfig: ConfigSource[F, NonEmptyString, NonEmptyList[Command]],
      buttonConfig: ConfigSource[F, String, Button],
    )

  implicit def componentsMonoid[F[_]: Monad: Parallel: RemoteControlErrors]: Monoid[Components[F]] =
    monoid[Components[F]]
}
