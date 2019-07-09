package io.janstenpickle.controller.stats.`macro`

import cats.{Apply, Monad, Parallel}
import cats.syntax.apply._
import cats.data.NonEmptyList
import cats.effect.Timer
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.{Macro, MacroErrors}
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.store.MacroStore
import io.janstenpickle.controller.switch.Switches
import natchez.Trace

object MacroStats {
  def apply[F[_]: Apply](underlying: Macro[F])(implicit stats: MacroStatsRecorder[F]): Macro[F] = new Macro[F] {
    override def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] =
      underlying.storeMacro(name, commands) *> stats.recordStoreMacro(name, commands)

    override def executeMacro(name: NonEmptyString): F[Unit] =
      underlying.executeMacro(name) *> stats.recordExecuteMacro(name)

    override def maybeExecuteMacro(name: NonEmptyString): F[Unit] =
      underlying.maybeExecuteMacro(name) *> stats.recordExecuteMacro(name)

    override def listMacros: F[List[NonEmptyString]] = underlying.listMacros
  }

  def apply[F[_]: Monad: Timer: Parallel: MacroErrors: MacroStatsRecorder: Trace](
    macroStore: MacroStore[F],
    remotes: RemoteControls[F],
    switches: Switches[F]
  ): Macro[F] =
    apply[F](Macro[F](macroStore, remotes, switches))
}
