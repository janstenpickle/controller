package io.janstenpickle.controller.switch.virtual

import cats.effect.{Clock, Concurrent, ExitCase, Resource, Sync, Timer}
import cats.instances.list._
import cats.instances.map._
import cats.instances.tuple._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, Eq, MonadError}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.{SwitchKey, SwitchMetadata, RemoteSwitchKey => ModelSwitchKey, _}
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.switches.store.SwitchStateStore
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import io.janstenpickle.trace4cats.inject.Trace

import scala.concurrent.duration._

object SwitchesForRemote {
  case class PollingConfig(pollInterval: FiniteDuration = 1.minute, errorThreshold: PosInt = PosInt(2))

  private implicit def switchEq[F[_]]: Eq[Switch[F]] = Eq.by(s => (s.device, s.name))

  private def make[F[_]: MonadError[*[_], Throwable]: Clock](
    virtualSwitches: ConfigSource[F, ModelSwitchKey, VirtualSwitch],
    remotes: RemoteControls[F],
    store: SwitchStateStore[F],
    eventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  ): F[Map[SwitchKey, Switch[F]]] =
    virtualSwitches.getConfig.map(_.values.map {
      case (_, virtual) =>
        SwitchKey(NonEmptyString.unsafeFrom(s"${virtual.remote}-${virtual.device}"), virtual.command) ->
          new Switch[F] {
            override val name: NonEmptyString = virtual.command
            override val device: NonEmptyString = virtual.device
            override val metadata: SwitchMetadata =
              SwitchMetadata(room = virtual.room.map(_.value), `type` = SwitchType.Virtual)

            val key: SwitchKey = SwitchKey(device, name)

            override def getState: F[State] = store.getState(virtual.remote, device, name)

            override def switchOn: F[Unit] =
              store
                .getState(virtual.remote, device, name)
                .flatMap {
                  case State.On => ().pure[F]
                  case State.Off =>
                    remotes.send(virtual.remote, virtual.commandSource, device, name) *> store
                      .setOn(virtual.remote, device, name) *> eventPublisher.publish1(
                      SwitchStateUpdateEvent(key, State.On)
                    )

                }
                .handleErrorWith { th =>
                  eventPublisher
                    .publish1(SwitchStateUpdateEvent(key, State.Off, Some(th.getMessage))) *> th.raiseError
                }

            override def switchOff: F[Unit] =
              store
                .getState(virtual.remote, device, name)
                .flatMap {
                  case State.On =>
                    remotes.send(virtual.remote, virtual.commandSource, device, name) *> store
                      .setOff(virtual.remote, device, name) *> eventPublisher.publish1(
                      SwitchStateUpdateEvent(key, State.Off)
                    )
                  case State.Off => ().pure[F]
                }
                .handleErrorWith { th =>
                  eventPublisher
                    .publish1(SwitchStateUpdateEvent(key, State.Off, Some(th.getMessage))) *> th.raiseError
                }

          }
    }.toMap)

  def polling[F[_]: Sync: Trace: Clock, G[_]: Concurrent: Timer](
    pollingConfig: PollingConfig,
    virtualSwitches: ConfigSource[F, ModelSwitchKey, VirtualSwitch],
    remotes: RemoteControls[F],
    state: SwitchStateStore[F],
    eventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, SwitchProvider[F]] =
    Resource.liftF(Slf4jLogger.fromName[F](s"switchesForRemotePoller")).flatMap { implicit logger =>
      DataPoller.traced[F, G, Map[SwitchKey, Switch[F]], SwitchProvider[F]]("switchesForRemote")(
        (_: Data[Map[SwitchKey, Switch[F]]]) => make(virtualSwitches, remotes, state, eventPublisher),
        pollingConfig.pollInterval,
        pollingConfig.errorThreshold,
        (_: Map[SwitchKey, Switch[F]], _: Map[SwitchKey, Switch[F]]) => Applicative[F].unit
      ) { (getData, _) =>
        new SwitchProvider[F] {
          override def getSwitches: F[Map[SwitchKey, Switch[F]]] = getData()
        }
      }
    }
}
