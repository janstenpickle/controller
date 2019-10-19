package io.janstenpickle.controller.store

import cats.Functor
import cats.data.NonEmptyList
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.model.Command

trait MacroStore[F[_]] {
  def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit]
  def loadMacro(name: NonEmptyString): F[Option[NonEmptyList[Command]]]
  def listMacros: F[List[NonEmptyString]]
}

object MacroStore {
  def fromConfigSource[F[_]: Functor](
    source: WritableConfigSource[F, NonEmptyString, NonEmptyList[Command]]
  ): MacroStore[F] =
    new MacroStore[F] {
      override def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] =
        source.upsert(name, commands).void

      override def loadMacro(name: NonEmptyString): F[Option[NonEmptyList[Command]]] =
        source.getValue(name)

      override def listMacros: F[List[NonEmptyString]] =
        source.getConfig.map(_.values.keys.toList)
    }
}
