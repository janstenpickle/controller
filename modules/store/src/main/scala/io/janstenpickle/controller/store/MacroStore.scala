package io.janstenpickle.controller.store

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Command

trait MacroStore[F[_]] {
  def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit]
  def loadMacro(name: NonEmptyString): F[Option[NonEmptyList[Command]]]
}
