package io.janstenpickle.controller.switch.virtual

import cats.MonadError
import cats.syntax.flatMap._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{State, SwitchKey}
import io.janstenpickle.controller.switches.store.SwitchStateStore
import io.janstenpickle.controller.switch.SwitchProvider

object SwitchDependentStore {
  def apply[F[_]](remotes: Map[NonEmptyString, SwitchKey], state: SwitchStateStore[F], provider: SwitchProvider[F])(
    implicit F: MonadError[F, Throwable]
  ): SwitchStateStore[F] = {
    def dependent[A](remote: NonEmptyString, op: F[A], default: F[A]): F[A] =
      remotes.get(remote) match {
        case None => op
        case Some(key) =>
          provider.getSwitches.flatMap(_.get(key) match {
            case None => default
            case Some(switch) =>
              switch.getState.flatMap {
                case State.On => op
                case State.Off => default
              }
          })
      }

    def doIfOn(remote: NonEmptyString, op: F[Unit]): F[Unit] = dependent(remote, op, F.unit)

    new SwitchStateStore[F] {
      override def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        doIfOn(remote, state.setOn(remote, device, name))

      override def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        doIfOn(remote, state.setOff(remote, device, name))

      override def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State] =
        dependent(remote, state.getState(remote, device, name), F.pure(State.Off))
    }
  }
}
