package io.janstenpickle.controller.broadlink.switch

import cats.effect._
import cats.syntax.apply._
import cats.syntax.functor._
import com.github.mob41.blapi.{SP1Device, SP2Device}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.control.switch.polling.{PollingSwitch, PollingSwitchErrors}
import io.janstenpickle.controller.broadlink.switch.SpSwitchConfig.{SP1, SP2}
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.switch.Switch

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object SpSwitch {
  case class PollingConfig(pollInterval: FiniteDuration = 2.seconds, errorThreshold: PosInt = PosInt(2))

  private def makeSp1[F[_]: Concurrent: ContextShift](
    config: SP1,
    store: SwitchStateStore[F],
    ec: ExecutionContext
  ): F[Switch[F]] = {
    def suspendErrorsEval[A](thunk: => A): F[A] = suspendErrorsEvalOn(thunk, ec)

    suspendErrors(new SP1Device(config.host.value, config.mac)).map { sp1 =>
      new Switch[F] {
        override def name: NonEmptyString = config.name

        override def device: NonEmptyString = NonEmptyString("SP1")

        override def getState: F[State] = store.getState(device, device, name)

        override def switchOn: F[Unit] =
          suspendErrorsEval(sp1.auth()) *> suspendErrorsEval(sp1.setPower(true)) *> store.setOn(device, device, name)

        override def switchOff: F[Unit] =
          suspendErrorsEval(sp1.auth()) *> suspendErrorsEval(sp1.setPower(false)) *> store.setOff(device, device, name)
      }
    }
  }

  private def makeSp2[F[_]: Concurrent: ContextShift](config: SP2, ec: ExecutionContext): F[Switch[F]] = {
    def suspendErrorsEval[A](thunk: => A): F[A] = suspendErrorsEvalOn(thunk, ec)

    suspendErrors(new SP2Device(config.host.value, config.mac)).map { sp2 =>
      new Switch[F] {
        override def name: NonEmptyString = config.name

        override def device: NonEmptyString = NonEmptyString("SP2")

        override def getState: F[State] =
          suspendErrorsEval(sp2.auth()) *> suspendErrorsEval(State.fromBoolean(sp2.getState))

        override def switchOn: F[Unit] = suspendErrorsEval(sp2.auth()) *> suspendErrorsEval(sp2.setState(true))

        override def switchOff: F[Unit] = suspendErrorsEval(sp2.auth()) *> suspendErrorsEval(sp2.setState(false))
      }
    }
  }

  def apply[F[_]: Concurrent: ContextShift](
    config: SpSwitchConfig,
    store: SwitchStateStore[F],
    ec: ExecutionContext
  ): F[Switch[F]] =
    config match {
      case conf: SP1 => makeSp1[F](conf, store, ec)
      case conf: SP2 => makeSp2[F](conf, ec)
    }

  def polling[F[_]: Concurrent: ContextShift: Timer: PollingSwitchErrors](
    config: SpSwitchConfig,
    pollingConfig: PollingConfig,
    store: SwitchStateStore[F],
    ec: ExecutionContext,
    onUpdate: State => F[Unit]
  ): Resource[F, Switch[F]] =
    Resource
      .liftF(apply(config, store, ec))
      .flatMap(PollingSwitch(_, pollingConfig.pollInterval, pollingConfig.errorThreshold, onUpdate))

}
