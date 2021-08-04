package io.janstenpickle.controller.switch.virtual

import cats.effect.kernel.Async
import cats.effect.{Clock, Resource, Sync}
import cats.instances.map._
import cats.instances.tuple._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Eq, MonadError}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.model.{SwitchKey, SwitchMetadata, RemoteSwitchKey => ModelSwitchKey, _}
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import io.janstenpickle.controller.switches.store.SwitchStateStore
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import org.typelevel.log4cats.slf4j.Slf4jLogger

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
        SwitchKey(NonEmptyString.unsafeFrom(s"${virtual.remote}-${virtual.device}"), virtual.name) ->
          new Switch[F] {
            override val name: NonEmptyString = virtual.name
            override val device: NonEmptyString = virtual.device
            override val metadata: SwitchMetadata =
              SwitchMetadata(room = virtual.room.map(_.value), `type` = SwitchType.Virtual)

            val key: SwitchKey = SwitchKey(device, name)

            val onCommand: String = virtual match {
              case v: VirtualSwitch.Toggle => v.command.value
              case v: VirtualSwitch.OnOff => v.on.value
            }

            val offCommand: String = virtual match {
              case v: VirtualSwitch.Toggle => v.command.value
              case v: VirtualSwitch.OnOff => v.off.value
            }

            override def getState: F[State] = store.getState(virtual.remote, device, name)

            override def switchOn: F[Unit] =
              store
                .getState(virtual.remote, device, name)
                .flatMap {
                  case State.On => ().pure[F]
                  case State.Off =>
                    remotes.send(virtual.remote, virtual.commandSource, device, NonEmptyString.unsafeFrom(onCommand)) *> store
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
                    remotes
                      .send(virtual.remote, virtual.commandSource, device, NonEmptyString.unsafeFrom(offCommand)) *> store
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

  def polling[F[_]: Sync: Trace: Clock, G[_]: Async](
    pollingConfig: PollingConfig,
    virtualSwitches: ConfigSource[F, ModelSwitchKey, VirtualSwitch],
    remotes: RemoteControls[F],
    state: SwitchStateStore[F],
    eventPublisher: EventPublisher[F, SwitchStateUpdateEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, SwitchProvider[F]] =
    Resource.eval(Slf4jLogger.fromName[F](s"switchesForRemotePoller")).flatMap { implicit logger =>
      DataPoller.traced[F, G, Map[SwitchKey, Switch[F]], SwitchProvider[F]]("switchesForRemote")(
        (_: Data[Map[SwitchKey, Switch[F]]]) => make(virtualSwitches, remotes, state, eventPublisher),
        pollingConfig.pollInterval,
        pollingConfig.errorThreshold,
        (_: Map[SwitchKey, Switch[F]], _: Map[SwitchKey, Switch[F]]) => Applicative[F].unit,
        k
      ) { (getData, _) =>
        new SwitchProvider[F] {
          override def getSwitches: F[Map[SwitchKey, Switch[F]]] = getData()
        }
      }
    }
}
