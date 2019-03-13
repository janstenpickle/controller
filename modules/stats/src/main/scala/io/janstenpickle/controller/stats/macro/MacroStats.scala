package io.janstenpickle.controller.stats.`macro`

import cats.Apply
import cats.syntax.apply._
import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.model.Command

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
}
