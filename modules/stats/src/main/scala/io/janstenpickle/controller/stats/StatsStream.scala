package io.janstenpickle.controller.stats

import cats.effect.{Concurrent, Resource, Timer}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.concurrent.Topic
import io.janstenpickle.controller.`macro`.{Macro, MacroErrors}
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.{Activity => ActivityModel, Button, Remote}
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.stats.`macro`.{MacroPoller, MacroStats, MacroStatsRecorder}
import io.janstenpickle.controller.stats.activity.{ActivityPoller, ActivityStats, ActivityStatsRecorder}
import io.janstenpickle.controller.stats.button.ButtonPoller
import io.janstenpickle.controller.stats.remote.{RemoteControlsStats, RemoteStatsRecorder, RemotesPoller}
import io.janstenpickle.controller.stats.switch.{SwitchStatsRecorder, SwitchesPoller, SwitchesStats}
import io.janstenpickle.controller.store.MacroStore
import io.janstenpickle.controller.switch.{SwitchProvider, Switches}

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

  def apply[F[_]: Concurrent: Timer: MacroErrors](
    config: Config,
    remote: RemoteControls[F],
    switch: Switches[F],
    activities: ConfigSource[F, String, ActivityModel],
    buttons: ConfigSource[F, String, Button],
    macros: MacroStore[F],
    remotes: ConfigSource[F, NonEmptyString, Remote],
    switches: SwitchProvider[F]
  )(
    makeActivity: Macro[F] => Activity[F],
    makeMacro: (RemoteControls[F], Switches[F]) => Macro[F]
  )(configUpdate: Topic[F, Boolean], switchUpdate: Topic[F, Boolean]): Resource[F, Instrumented[F]] = {
    def instrumentedComponents(
      implicit ar: ActivityStatsRecorder[F],
      mr: MacroStatsRecorder[F],
      rr: RemoteStatsRecorder[F],
      switchStatsRecorder: SwitchStatsRecorder[F]
    ): (Activity[F], Macro[F], RemoteControls[F], Switches[F]) = {
      val instrumentedRemotes = RemoteControlsStats(remote)
      val instrumentedSwitches = SwitchesStats(switch)
      val instrumentedMacro = MacroStats(makeMacro(instrumentedRemotes, instrumentedSwitches))

      (ActivityStats(makeActivity(instrumentedMacro)), instrumentedMacro, instrumentedRemotes, instrumentedSwitches)
    }

    for {
      activityRecorder <- ActivityStatsRecorder.stream[F](config.maxQueued)
      macroRecorder <- MacroStatsRecorder.stream[F](config.maxQueued)
      remoteRecorder <- RemoteStatsRecorder.stream[F](config.maxQueued)
      switchRecorder <- SwitchStatsRecorder.stream[F](config.maxQueued)
    } yield {
      val instrumented =
        instrumentedComponents(activityRecorder._1, macroRecorder._1, remoteRecorder._1, switchRecorder._1)

      Instrumented(
        instrumented._1,
        instrumented._2,
        instrumented._3,
        instrumented._4,
        activityRecorder._2
          .merge(macroRecorder._2)
          .merge(remoteRecorder._2)
          .merge(switchRecorder._2)
          .merge(ActivityPoller(config.pollInterval, activities, configUpdate))
          .merge(ButtonPoller(config.pollInterval, buttons, configUpdate))
          .merge(MacroPoller(config.pollInterval, config.parallelism, macros, configUpdate))
          .merge(RemotesPoller(config.pollInterval, remotes, configUpdate))
          .merge(SwitchesPoller(config.pollInterval, config.parallelism, switches, switchUpdate))
      )
    }
  }
}
