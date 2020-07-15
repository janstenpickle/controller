package io.janstenpickle.controller.switches.store

import cats.Apply
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue

object TracedSwitchStateStore {
  def apply[F[_]: Apply](store: SwitchStateStore[F], `type`: String, extraFields: (String, AttributeValue)*)(
    implicit trace: Trace[F]
  ): SwitchStateStore[F] =
    new SwitchStateStore[F] {
      private def span[A](n: String, remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString)(
        k: F[A]
      ): F[A] =
        trace.span(n) {
          trace
            .putAll(
              Seq[(String, AttributeValue)](
                "remote" -> remote.value,
                "device" -> device.value,
                "name" -> name.value,
                "store.type" -> `type`
              ) ++ extraFields: _*
            ) *> k
        }

      override def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("switch.store.set.on", remote, device, name) { store.setOn(remote, device, name) }

      override def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("switch.store.set.off", remote, device, name) { store.setOff(remote, device, name) }

      override def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State] =
        span("switch.store.get.state", remote, device, name) { store.getState(remote, device, name) }
    }
}
