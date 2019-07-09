package io.janstenpickle.control.switch.polling

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.syntax.apply._
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import io.janstenpickle.controller.switch.Switch
import natchez.Trace

import scala.concurrent.duration.FiniteDuration

object PollingSwitch {
  implicit val stateEmpty: Empty[State] = Empty[State](State.Off)

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    underlying: Switch[F],
    pollInterval: FiniteDuration,
    errorThreshold: PosInt,
    onUpdate: State => F[Unit]
  )(implicit errors: PollingSwitchErrors[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, Switch[F]] =
    DataPoller.traced[F, G, State, Switch[F]](s"${underlying.name.value}Switch")(
      (_: Data[State]) => underlying.getState,
      pollInterval,
      errorThreshold,
      (data: Data[State], th: Throwable) => errors.pollError[State](underlying.name, data.value, data.updated, th),
      onUpdate
    ) { (get, update) =>
      new Switch[F] {
        override def name: NonEmptyString = underlying.name
        override def device: NonEmptyString = underlying.device
        override def getState: F[State] = get()
        override def switchOn: F[Unit] = underlying.switchOn *> update(State.On)
        override def switchOff: F[Unit] = underlying.switchOff *> update(State.Off)
      }
    }
}
