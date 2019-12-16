package io.janstenpickle.controller.broadlink.switch

import cats.effect._
import cats.effect.concurrent.Ref
import cats.instances.string._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Eq}
import com.github.mob41.blapi.{BLDevice, SP1Device, SP2Device}
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.auto._
import io.janstenpickle.controller.broadlink.switch.SpSwitchConfig.{SP1, SP2, SP3}
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.switch.Switch
import natchez.{Trace, TraceValue}
import cats.syntax.applicativeError._
import cats.syntax.apply._
import io.janstenpickle.controller.switch.model.SwitchKey

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

trait SpSwitch[F[_]] extends Switch[F] {
  def refresh: F[Unit]
  def host: String
  def mac: String
}

object SpSwitch {
  case class CommandTimeout(device: String, message: String)
      extends RuntimeException(s"Command timed out on '$device' after $message")
      with NoStackTrace

  val manufacturerField: (String, TraceValue) = "manufacturer" -> "broadlink"

  implicit def spSwitchEq[F[_]]: Eq[SpSwitch[F]] = Eq.by { dev =>
    s"${dev.host}_${dev.mac}"
  }

  private def timeoutOp[F[_]: Concurrent: Timer, A](
    fa: F[A],
    duration: FiniteDuration,
    deviceName: NonEmptyString
  ): F[A] =
    Concurrent
      .timeout(fa, duration)
      .recoverWith {
        case ex: TimeoutException => CommandTimeout(deviceName, ex.getMessage).raiseError
      }

  def makeSp1[F[_]: Concurrent: Timer: ContextShift](config: SP1, store: SwitchStateStore[F], blocker: Blocker)(
    implicit trace: Trace[F]
  ): F[SpSwitch[F]] =
    Sync[F].delay(new SP1Device(config.host, config.mac)).map { sp1 =>
      makeSp1[F](config.name, config.timeout, sp1, store, blocker)
    }

  def makeSp1[F[_]: Concurrent: Timer: ContextShift](
    deviceName: NonEmptyString,
    timeout: FiniteDuration,
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
      timeoutOp(for {
        _ <- trace.span("auth") { blocker.delay(sp1.auth()) }
        _ <- trace.span("setPower") { blocker.delay(sp1.setPower(true)) }
        _ <- store.setOn(device, device, name)
      } yield (), timeout, deviceName)

    override def switchOff: F[Unit] =
      timeoutOp(for {
        _ <- trace.span("auth") { blocker.delay(sp1.auth()) }
        _ <- trace.span("setPower") { blocker.delay(sp1.setPower(true)) }
        _ <- store.setOff(device, device, name)
      } yield (), timeout, deviceName)

    override def refresh: F[Unit] = Applicative[F].unit
  }

  def makeSp23[F[_]: Concurrent: Timer: ContextShift](
    deviceName: NonEmptyString,
    timeout: FiniteDuration,
    sp: SP2Device,
    blocker: Blocker,
    onSwitchUpdate: SwitchKey => F[Unit]
  )(implicit trace: Trace[F]): F[SpSwitch[F]] = {

    def _getState: F[State] =
      timeoutOp(for {
        _ <- trace.span("auth") {
          blocker.delay(sp.auth())
        }
        state <- trace.span("getState") {
          blocker.delay(State.fromBoolean(sp.getState))
        }
      } yield state, timeout, deviceName)

    for {
      initState <- _getState
      state <- Ref.of(initState)
    } yield
      new SpSwitch[F] {

        override val name: NonEmptyString = deviceName
        override val device: NonEmptyString = NonEmptyString("SP")
        override val mac: String = sp.getMac.getMacString
        override val host: String = sp.getHost

        private val switchKey = SwitchKey(device, name)

        override def getState: F[State] = state.get

        override def switchOn: F[Unit] =
          timeoutOp(for {
            _ <- trace.span("auth") {
              blocker.delay(sp.auth())
            }
            _ <- trace.span("setState") {
              blocker.delay(sp.setState(true))
            }
            _ <- state.set(State.On)
          } yield (), timeout, deviceName)

        override def switchOff: F[Unit] =
          timeoutOp(for {
            _ <- trace.span("auth") {
              blocker.delay(sp.auth())
            }
            _ <- trace.span("setState") {
              blocker.delay(sp.setState(false))
            }
            _ <- state.set(State.Off)
          } yield (), timeout, deviceName)

        override def refresh: F[Unit] =
          for {
            current <- state.get
            newState <- _getState
            _ <- if (current != newState) state.set(newState) *> onSwitchUpdate(switchKey) else Applicative[F].unit
          } yield ()
      }
  }

  private def makeSp2[F[_]: Concurrent: Timer: ContextShift: Trace](
    config: SP2,
    blocker: Blocker,
    onSwitchUpdate: SwitchKey => F[Unit]
  ): F[SpSwitch[F]] =
    Sync[F].delay(new SP2Device(config.host, config.mac)).flatMap { sp2 =>
      makeSp23(config.name, config.timeout, sp2, blocker, onSwitchUpdate)
    }

  private def makeSp3[F[_]: Concurrent: Timer: ContextShift: Trace](
    config: SP3,
    blocker: Blocker,
    onSwitchUpdate: SwitchKey => F[Unit]
  ): F[SpSwitch[F]] =
    Sync[F]
      .delay(BLDevice.createInstance(BLDevice.DEV_SP3, config.host, config.mac).asInstanceOf[SP2Device])
      .flatMap { sp2 =>
        makeSp23(config.name, config.timeout, sp2, blocker, onSwitchUpdate)
      }

  def apply[F[_]: Concurrent: Timer: ContextShift: Trace](
    config: SpSwitchConfig,
    store: SwitchStateStore[F],
    blocker: Blocker,
    onSwitchUpdate: SwitchKey => F[Unit]
  ): F[SpSwitch[F]] =
    config match {
      case conf: SP1 => makeSp1[F](conf, store, blocker)
      case conf: SP2 => makeSp2[F](conf, blocker, onSwitchUpdate)
      case conf: SP3 => makeSp3[F](conf, blocker, onSwitchUpdate)
    }
}
