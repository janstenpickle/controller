package io.janstenpickle.controller.switch.virtual

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.map._
import cats.instances.tuple._
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Eq, Monad}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.{SwitchKey => ModelSwitchKey, _}
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Metadata, Switch, SwitchProvider, SwitchType}
import natchez.Trace

import scala.concurrent.duration._

object SwitchesForRemote {
  case class PollingConfig(pollInterval: FiniteDuration = 1.minute, errorThreshold: PosInt = PosInt(2))

  private implicit def switchEq[F[_]]: Eq[Switch[F]] = Eq.by(s => (s.device, s.name))

  private def make[F[_]: Monad](
    virtualSwitches: ConfigSource[F, ModelSwitchKey, VirtualSwitch],
    remotes: RemoteControls[F],
    store: SwitchStateStore[F]
  ): F[Map[SwitchKey, Switch[F]]] =
    virtualSwitches.getConfig.map(_.values.map {
      case (_, virtual) =>
        SwitchKey(NonEmptyString.unsafeFrom(s"${virtual.remote}-${virtual.device}"), virtual.command) ->
          new Switch[F] {
            override def name: NonEmptyString = virtual.command
            override def device: NonEmptyString = virtual.device

            override def getState: F[State] = store.getState(virtual.remote, device, name)

            override def switchOn: F[Unit] =
              store.getState(virtual.remote, device, name).flatMap {
                case State.On => ().pure
                case State.Off =>
                  remotes.send(virtual.remote, virtual.commandSource, device, name) *> store
                    .setOn(virtual.remote, device, name)

              }

            override def switchOff: F[Unit] =
              store.getState(virtual.remote, device, name).flatMap {
                case State.On =>
                  remotes.send(virtual.remote, virtual.commandSource, device, name) *> store
                    .setOff(virtual.remote, device, name)
                case State.Off => ().pure
              }

            override def metadata: Metadata = Metadata(room = virtual.room.map(_.value), `type` = SwitchType.Virtual)
          }
    }.toMap)

  def polling[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    pollingConfig: PollingConfig,
    virtualSwitches: ConfigSource[F, ModelSwitchKey, VirtualSwitch],
    remotes: RemoteControls[F],
    state: SwitchStateStore[F],
    onUpdate: Map[SwitchKey, Switch[F]] => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, SwitchProvider[F]] =
    Resource.liftF(Slf4jLogger.fromName[F](s"switchesForRemotePoller")).flatMap { implicit logger =>
      DataPoller.traced[F, G, Map[SwitchKey, Switch[F]], SwitchProvider[F]]("switchesForRemote")(
        (_: Data[Map[SwitchKey, Switch[F]]]) => make(virtualSwitches, remotes, state),
        pollingConfig.pollInterval,
        pollingConfig.errorThreshold,
        onUpdate
      ) { (getData, _) =>
        new SwitchProvider[F] {
          override def getSwitches: F[Map[SwitchKey, Switch[F]]] = getData()
        }
      }
    }
}
