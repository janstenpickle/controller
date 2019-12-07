package io.janstenpickle.controller.broadlink.switch

import cats.{Applicative, Eq}
import cats.effect._
import cats.effect.concurrent.Ref
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
import cats.instances.string._

import scala.concurrent.duration._

trait SpSwitch[F[_]] extends Switch[F] {
  def refresh: F[Unit]
  def host: String
  def mac: String
}

object SpSwitch {
  case class PollingConfig(pollInterval: FiniteDuration = 2.seconds, errorThreshold: PosInt = PosInt(2))

  val manufacturerField: (String, TraceValue) = "manufacturer" -> "broadlink"

  implicit def spSwitchEq[F[_]]: Eq[SpSwitch[F]] = Eq.by { dev =>
    s"${dev.host}_${dev.mac}"
  }

  def makeSp1[F[_]: Sync: ContextShift](config: SP1, store: SwitchStateStore[F], blocker: Blocker)(
    implicit trace: Trace[F]
  ): F[SpSwitch[F]] =
    Sync[F].delay(new SP1Device(config.host.value, config.mac)).map { sp1 =>
      makeSp1[F](config.name, sp1, store, blocker)
    }

  def makeSp1[F[_]: Sync: ContextShift](
    deviceName: NonEmptyString,
    sp1: SP1Device,
    store: SwitchStateStore[F],
    blocker: Blocker
  )(implicit trace: Trace[F]): SpSwitch[F] = new SpSwitch[F] {
    override val name: NonEmptyString = deviceName
    override val device: NonEmptyString = NonEmptyString("SP")
    override val mac: String = sp1.getMac.getMacString
    override val host: String = sp1.getHost

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

    override def refresh: F[Unit] = Applicative[F].unit
  }

  def makeSp23[F[_]: Sync: ContextShift](deviceName: NonEmptyString, sp: SP2Device, blocker: Blocker)(
    implicit trace: Trace[F]
  ): F[SpSwitch[F]] = {
    def _getState: F[State] =
      for {
        _ <- trace.span("auth") {
          blocker.delay(sp.auth())
        }
        state <- trace.span("getState") {
          blocker.delay(State.fromBoolean(sp.getState))
        }
      } yield state

    for {
      initState <- _getState
      state <- Ref.of(initState)
    } yield
      new SpSwitch[F] {
        override val name: NonEmptyString = deviceName
        override val device: NonEmptyString = NonEmptyString("SP")
        override val mac: String = sp.getMac.getMacString
        override val host: String = sp.getHost

        override def getState: F[State] = state.get

        override def switchOn: F[Unit] =
          for {
            _ <- trace.span("auth") {
              blocker.delay(sp.auth())
            }
            _ <- trace.span("setState") {
              blocker.delay(sp.setState(true))
            }
            _ <- state.set(State.On)
          } yield ()

        override def switchOff: F[Unit] =
          for {
            _ <- trace.span("auth") {
              blocker.delay(sp.auth())
            }
            _ <- trace.span("setState") {
              blocker.delay(sp.setState(false))
            }
            _ <- state.set(State.Off)
          } yield ()

        override def refresh: F[Unit] = _getState.flatMap(state.set)
      }
  }

  private def makeSp2[F[_]: Sync: ContextShift: Trace](config: SP2, blocker: Blocker): F[SpSwitch[F]] =
    Sync[F].delay(new SP2Device(config.host.value, config.mac)).flatMap { sp2 =>
      makeSp23(config.name, sp2, blocker)
    }

  private def makeSp3[F[_]: Sync: ContextShift: Trace](config: SP3, blocker: Blocker): F[SpSwitch[F]] =
    Sync[F]
      .delay(BLDevice.createInstance(BLDevice.DEV_SP3, config.host.value, config.mac).asInstanceOf[SP2Device])
      .flatMap { sp2 =>
        makeSp23(config.name, sp2, blocker)
      }

  def apply[F[_]: Sync: ContextShift: Trace](
    config: SpSwitchConfig,
    store: SwitchStateStore[F],
    blocker: Blocker
  ): F[SpSwitch[F]] =
    config match {
      case conf: SP1 => makeSp1[F](conf, store, blocker)
      case conf: SP2 => makeSp2[F](conf, blocker)
      case conf: SP3 => makeSp3[F](conf, blocker)
    }
}
