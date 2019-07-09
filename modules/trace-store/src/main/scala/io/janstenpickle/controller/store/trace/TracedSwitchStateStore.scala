package io.janstenpickle.controller.store.trace

import cats.Apply
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.store.SwitchStateStore
import natchez.{Trace, TraceValue}
import cats.syntax.apply._

object TracedSwitchStateStore {
  def apply[F[_]: Apply](store: SwitchStateStore[F], `type`: String, extraFields: (String, TraceValue)*)(
    implicit trace: Trace[F]
  ): SwitchStateStore[F] =
    new SwitchStateStore[F] {
      private def span[A](n: String, remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString)(
        k: F[A]
      ): F[A] =
        trace.span(n) {
          trace
            .put(
              Seq[(String, TraceValue)](
                "remote" -> remote.value,
                "device" -> device.value,
                "name" -> name.value,
                "store.type" -> `type`
              ) ++ extraFields: _*
            ) *> k
        }

      override def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("setOn", remote, device, name) { store.setOn(remote, device, name) }

      override def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("setOff", remote, device, name) { store.setOff(remote, device, name) }

      override def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State] =
        span("getState", remote, device, name) { store.getState(remote, device, name) }
    }
}
