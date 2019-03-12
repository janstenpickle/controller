package io.janstenpickle.controller.switch.virtual

import cats.effect.{Concurrent, Resource, Timer}
import cats.instances.map._
import cats.instances.tuple._
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Eq, Monad}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model._
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}

import scala.concurrent.duration._

object SwitchesForRemote {
  case class PollingConfig(pollInterval: FiniteDuration = 1.minute, errorThreshold: PosInt = PosInt(2))

  private implicit def switchEq[F[_]]: Eq[Switch[F]] = Eq.by(s => (s.device, s.name))

  private def make[F[_]: Monad](remotes: RemoteControls[F], store: SwitchStateStore[F]): F[Map[SwitchKey, Switch[F]]] =
    remotes.listCommands.map(_.map { command =>
      SwitchKey(NonEmptyString.unsafeFrom(s"${command.remote}-${command.device}"), command.name) ->
        new Switch[F] {
          override def name: NonEmptyString = command.name
          override def device: NonEmptyString = command.device

          override def getState: F[State] = store.getState(command.remote, device, name)

          override def switchOn: F[Unit] =
            store.getState(command.remote, device, name).flatMap {
              case State.On => ().pure
              case State.Off => remotes.send(command.remote, device, name) *> store.setOn(command.remote, device, name)

            }

          override def switchOff: F[Unit] =
            store.getState(command.remote, device, name).flatMap {
              case State.On => remotes.send(command.remote, device, name) *> store.setOff(command.remote, device, name)
              case State.Off => ().pure
            }
        }
    }.toMap)

  def polling[F[_]: Concurrent: Timer](
    config: PollingConfig,
    remotes: RemoteControls[F],
    state: SwitchStateStore[F],
    onUpdate: Map[SwitchKey, Switch[F]] => F[Unit]
  ): Resource[F, SwitchProvider[F]] =
    DataPoller[F, Map[SwitchKey, Switch[F]], SwitchProvider[F]](
      (_: Data[Map[SwitchKey, Switch[F]]]) => make(remotes, state),
      config.pollInterval,
      config.errorThreshold,
      onUpdate
    ) { (getData, _) =>
      new SwitchProvider[F] {
        override def getSwitches: F[Map[SwitchKey, Switch[F]]] = getData()
      }
    }
}
