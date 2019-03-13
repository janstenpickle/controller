package io.janstenpickle.controller.stats

import cats.effect.{Concurrent, Resource, Timer}
import eu.timepit.refined.types.numeric.PosInt
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.configsource.{ActivityConfigSource, ButtonConfigSource, RemoteConfigSource}
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.stats.`macro`.{MacroPoller, MacroStats, MacroStatsRecorder}
import io.janstenpickle.controller.stats.activity.{ActivityPoller, ActivityStats, ActivityStatsRecorder}
import io.janstenpickle.controller.stats.button.ButtonPoller
import io.janstenpickle.controller.stats.remote.{RemoteControlsStats, RemoteStatsRecorder, RemotesPoller}
import io.janstenpickle.controller.stats.switch.{SwitchStatsRecorder, SwitchesPoller, SwitchesStats}
import io.janstenpickle.controller.store.MacroStore
import io.janstenpickle.controller.switch.{SwitchProvider, Switches}

import fs2.Stream

import scala.concurrent.duration._

object StatsStream {
  case class Config(
    pollInterval: FiniteDuration = 30.seconds,
    parallelism: PosInt = PosInt(2),
    maxQueued: PosInt = PosInt(3)
  )

  case class Recorders[F[_]](
    activity: ActivityStatsRecorder[F],
    `macro`: MacroStatsRecorder[F],
    remote: RemoteStatsRecorder[F],
    switch: SwitchStatsRecorder[F]
  )

  case class Instrumented[F[_]](
    activity: Activity[F],
    `macro`: Macro[F],
    remote: RemoteControls[F],
    switch: Switches[F],
    statsStream: Stream[F, Stats]
  )

  def apply[F[_]: Concurrent: Timer](
    config: Config,
    activity: Activity[F],
    `macro`: Macro[F],
    remote: RemoteControls[F],
    switch: Switches[F],
    activities: ActivityConfigSource[F],
    buttons: ButtonConfigSource[F],
    macros: MacroStore[F],
    remotes: RemoteConfigSource[F],
    switches: SwitchProvider[F]
  ): Resource[F, Instrumented[F]] =
    for {
      activityRecorder <- ActivityStatsRecorder.stream[F](config.maxQueued)
      macroRecorder <- MacroStatsRecorder.stream[F](config.maxQueued)
      remoteRecorder <- RemoteStatsRecorder.stream[F](config.maxQueued)
      switchRecorder <- SwitchStatsRecorder.stream[F](config.maxQueued)
    } yield {
      implicit val (ar, mr, rr, sr) = (activityRecorder._1, macroRecorder._1, remoteRecorder._1, switchRecorder._1)

      Instrumented(
        ActivityStats(activity),
        MacroStats(`macro`),
        RemoteControlsStats(remote),
        SwitchesStats(switch),
        activityRecorder._2
          .merge(macroRecorder._2)
          .merge(remoteRecorder._2)
          .merge(switchRecorder._2)
          .merge(ActivityPoller(config.pollInterval, activities))
          .merge(ButtonPoller(config.pollInterval, buttons))
          .merge(MacroPoller(config.pollInterval, config.parallelism, macros))
          .merge(RemotesPoller(config.pollInterval, remotes))
          .merge(SwitchesPoller(config.pollInterval, config.parallelism, switches))
      )
    }
}
