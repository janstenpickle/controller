package io.janstenpickle.control.switch.polling

import cats.effect.{Concurrent, Resource, Timer}
import cats.kernel.Monoid
import cats.syntax.apply._
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.switch.{State, Switch}

import scala.concurrent.duration.FiniteDuration

object PollingSwitch {
  implicit val stateMonoid: Monoid[State] = new Monoid[State] {
    override def empty: State = State.Off

    override def combine(x: State, y: State): State = (x, y) match {
      case (State.Off, _) => State.Off
      case (_, State.Off) => State.Off
      case (State.On, State.On) => State.On
    }
  }

  def apply[F[_]: Concurrent: Timer](underlying: Switch[F], pollInterval: FiniteDuration, errorThreshold: PosInt)(
    implicit errors: PollingSwitchErrors[F]
  ): Resource[F, Switch[F]] =
    DataPoller[F, State, Switch[F]](
      (_: Data[State]) => underlying.getState,
      pollInterval,
      errorThreshold,
      (data: Data[State], th: Throwable) => errors.pollError(underlying.name, data.value, data.updated, th)
    ) { (get, update) =>
      new Switch[F] {
        override def name: NonEmptyString = underlying.name
        override def getState: F[State] = get()
        override def switchOn: F[Unit] = underlying.switchOn *> update(State.On)
        override def switchOff: F[Unit] = underlying.switchOff *> update(State.Off)
      }
    }
}
