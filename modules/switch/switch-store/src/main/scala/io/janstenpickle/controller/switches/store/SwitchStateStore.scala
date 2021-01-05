package io.janstenpickle.controller.switches.store

import cats.Functor
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.model.{RemoteSwitchKey, State}

trait SwitchStateStore[F[_]] {
  def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit]
  def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit]
  def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State]
}

object SwitchStateStore {
  def fromConfigSource[F[_]: Functor](source: WritableConfigSource[F, RemoteSwitchKey, State]): SwitchStateStore[F] =
    new SwitchStateStore[F] {
      override def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        source.upsert(RemoteSwitchKey(remote, device, name), State.On).void

      override def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        source.upsert(RemoteSwitchKey(remote, device, name), State.Off).void

      override def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State] =
        source.getValue(RemoteSwitchKey(remote, device, name)).map(_.getOrElse(State.Off))
    }
}
