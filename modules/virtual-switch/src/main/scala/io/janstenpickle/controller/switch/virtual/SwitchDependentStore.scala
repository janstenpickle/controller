package io.janstenpickle.controller.switch.virtual

import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Monad, MonadError, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}

object SwitchDependentStore {
  def apply[F[_]](state: SwitchStateStore[F], switches: Map[NonEmptyString, Switch[F]])(
    implicit F: Monad[F]
  ): SwitchStateStore[F] = {
    def doIfOn(remote: NonEmptyString, op: F[Unit]): F[Unit] =
      switches
        .get(remote)
        .fold(op)(_.getState.flatMap {
          case State.On => op
          case State.Off => F.unit
        })

    new SwitchStateStore[F] {
      override def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        doIfOn(remote, state.setOn(remote, device, name))

      override def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        doIfOn(remote, state.setOff(remote, device, name))

      override def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State] =
        switches
          .get(remote)
          .fold(state.getState(remote, device, name))(_.getState.flatMap {
            case State.On => state.getState(remote, device, name)
            case State.Off => F.pure(State.Off)
          })
    }
  }

  def fromProvider[F[_]: Parallel](
    remotes: Map[NonEmptyString, SwitchKey],
    state: SwitchStateStore[F],
    provider: SwitchProvider[F]
  )(implicit F: MonadError[F, Throwable]): F[SwitchStateStore[F]] =
    provider.getSwitches.flatMap { switches =>
      remotes.toList
        .parTraverse[F, (NonEmptyString, Switch[F])] {
          case (remote, key) =>
            switches.get(key) match {
              case None =>
                F.raiseError(
                  new RuntimeException(
                    s"Could not find switch '${key.name.value}' of device '${key.device.value}' for remote '${remote.value}'"
                  )
                )
              case Some(switch) => F.pure((remote, switch))
            }
        }
        .map { s =>
          apply[F](state, s.toMap)
        }

    }
}
