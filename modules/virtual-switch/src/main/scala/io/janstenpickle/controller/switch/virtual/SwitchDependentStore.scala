package io.janstenpickle.controller.switch.virtual

import cats.syntax.flatMap._
import cats.{Monad, MonadError}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}

object SwitchDependentStore {
  def apply[F[_]](state: SwitchStateStore[F], switch: Switch[F])(implicit F: Monad[F]): SwitchStateStore[F] = {
    def doIfOn(op: F[Unit]): F[Unit] = switch.getState.flatMap {
      case State.On => op
      case State.Off => F.unit
    }

    new SwitchStateStore[F] {
      override def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        doIfOn(state.setOn(remote, device, name))

      override def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        doIfOn(state.setOff(remote, device, name))

      override def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State] =
        switch.getState.flatMap {
          case State.On => state.getState(remote, device, name)
          case State.Off => F.pure(State.Off)
        }
    }
  }

  def fromProvider[F[_]](
    switchDevice: NonEmptyString,
    switchName: NonEmptyString,
    state: SwitchStateStore[F],
    provider: SwitchProvider[F]
  )(implicit F: MonadError[F, Throwable]): F[SwitchStateStore[F]] =
    provider.getSwitches.flatMap(_.get(SwitchKey(switchDevice, switchName)) match {
      case None =>
        F.raiseError[SwitchStateStore[F]](
          new RuntimeException(s"Could not find switch '${switchName.value}' of device ${switchDevice.value}")
        )
      case Some(switch) => F.pure(apply[F](state, switch))
    })
}
