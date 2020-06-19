package io.janstenpickle.controller.`macro`.store

import cats.{ApplicativeError, Functor}
import cats.data.NonEmptyList
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.{ConfigSource, WritableConfigSource}
import io.janstenpickle.controller.model.Command

import cats.syntax.applicativeError._

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

  def fromReadOnlyConfigSource[F[_]: ApplicativeError[*[_], Throwable]](
    source: ConfigSource[F, NonEmptyString, NonEmptyList[Command]]
  ): MacroStore[F] =
    new MacroStore[F] {
      override def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] =
        new RuntimeException("Macro storage not supported").raiseError

      override def loadMacro(name: NonEmptyString): F[Option[NonEmptyList[Command]]] = source.getValue(name)

      override def listMacros: F[List[NonEmptyString]] = source.getConfig.map(_.values.keys.toList)

    }
}
