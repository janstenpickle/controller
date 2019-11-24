package io.janstenpickle.controller.broadlink.switch

import cats.effect._
import cats.syntax.functor._
import cats.syntax.flatMap._
import com.github.mob41.blapi.{BLDevice, SP1Device, SP2Device}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.control.switch.polling.{PollingSwitch, PollingSwitchErrors}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.switch.SpSwitchConfig.{SP1, SP2, SP3}
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.switch.Switch
import io.janstenpickle.controller.switch.trace.TracedSwitch
import natchez.{Trace, TraceValue}

import scala.concurrent.duration._

object SpSwitch {
  case class PollingConfig(pollInterval: FiniteDuration = 2.seconds, errorThreshold: PosInt = PosInt(2))

  private val manufacturerField: (String, TraceValue) = "manufacturer" -> "broadlink"

  private def makeSp1[F[_]: Sync: ContextShift](config: SP1, store: SwitchStateStore[F], blocker: Blocker)(
    implicit trace: Trace[F]
  ): F[Switch[F]] =
    Sync[F].delay(new SP1Device(config.host.value, config.mac)).map { sp1 =>
      TracedSwitch(
        new Switch[F] {
          override val name: NonEmptyString = config.name
          override val device: NonEmptyString = NonEmptyString("SP1")

          override def getState: F[State] = store.getState(device, device, name)

          override def switchOn: F[Unit] =
            for {
              _ <- trace.span("auth") { blocker.delay(sp1.auth()) }
              _ <- trace.span("setPower") { blocker.delay(sp1.setPower(true)) }
              _ <- store.setOn(device, device, name)
            } yield ()

          override def switchOff: F[Unit] =
            for {
              _ <- trace.span("auth") { blocker.delay(sp1.auth()) }
              _ <- trace.span("setPower") { blocker.delay(sp1.setPower(true)) }
              _ <- store.setOff(device, device, name)
            } yield ()
        },
        manufacturerField
      )
    }

  private def makeSp23[F[_]: Sync: ContextShift](
    deviceName: NonEmptyString,
    deviceType: NonEmptyString,
    sp: SP2Device,
    blocker: Blocker
  )(implicit trace: Trace[F]) =
    TracedSwitch(
      new Switch[F] {
        override val name: NonEmptyString = deviceName
        override val device: NonEmptyString = deviceType

        override def getState: F[State] =
          for {
            _ <- trace.span("auth") { blocker.delay(sp.auth()) }
            state <- trace.span("getState") { blocker.delay(State.fromBoolean(sp.getState)) }
          } yield state

        override def switchOn: F[Unit] =
          for {
            _ <- trace.span("auth") { blocker.delay(sp.auth()) }
            _ <- trace.span("setState") { blocker.delay(sp.setState(true)) }
          } yield ()

        override def switchOff: F[Unit] =
          for {
            _ <- trace.span("auth") { blocker.delay(sp.auth()) }
            _ <- trace.span("setState") { blocker.delay(sp.setState(false)) }
          } yield ()
      },
      manufacturerField
    )

  private def makeSp2[F[_]: Sync: ContextShift: Trace](config: SP2, blocker: Blocker): F[Switch[F]] =
    Sync[F].delay(new SP2Device(config.host.value, config.mac)).map { sp2 =>
      makeSp23(config.name, NonEmptyString("SP2"), sp2, blocker)
    }

  private def makeSp3[F[_]: Sync: ContextShift: Trace](config: SP3, blocker: Blocker): F[Switch[F]] =
    Sync[F]
      .delay(BLDevice.createInstance(BLDevice.DEV_SP3, config.host.value, config.mac).asInstanceOf[SP2Device])
      .map { sp2 =>
        makeSp23(config.name, NonEmptyString("SP3"), sp2, blocker)
      }

  def apply[F[_]: Sync: ContextShift: Trace](
    config: SpSwitchConfig,
    store: SwitchStateStore[F],
    blocker: Blocker
  ): F[Switch[F]] =
    config match {
      case conf: SP1 => makeSp1[F](conf, store, blocker)
      case conf: SP2 => makeSp2[F](conf, blocker)
      case conf: SP3 => makeSp3[F](conf, blocker)
    }

  def polling[F[_]: Sync: ContextShift: PollingSwitchErrors: Trace, G[_]: Concurrent: Timer](
    config: SpSwitchConfig,
    pollingConfig: PollingConfig,
    store: SwitchStateStore[F],
    blocker: Blocker,
    onUpdate: State => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Switch[F]] =
    Resource
      .liftF(apply(config, store, blocker))
      .flatMap(PollingSwitch[F, G](_, pollingConfig.pollInterval, pollingConfig.errorThreshold, onUpdate))

}
