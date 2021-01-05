package io.janstenpickle.controller.switch.trace

import cats.{Apply, FlatMap}
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{State, SwitchMetadata}
import io.janstenpickle.controller.switch.Switch
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue

object TracedSwitch {
  def apply[F[_]: Apply](switch: Switch[F], extraFields: (String, AttributeValue)*)(
    implicit trace: Trace[F]
  ): Switch[F] =
    new Switch[F] {
      override val name: NonEmptyString = switch.name
      override val device: NonEmptyString = switch.device

      private val fields: Seq[(String, AttributeValue)] = extraFields ++ metadata.values.view
        .mapValues(StringValue(_)) ++ Seq[(String, AttributeValue)](
        "switch.name" -> name.value,
        "switch.device" -> device.value
      )

      private def span[A](name: String)(k: F[A]): F[A] =
        trace.span[A](name) { trace.putAll(fields: _*) *> k }

      override def getState: F[State] = span("switch.state") { switch.getState }
      override def switchOn: F[Unit] = span("switch.on") { switch.switchOn }
      override def switchOff: F[Unit] = span("switch.off") { switch.switchOff }
      override def toggle(implicit F: FlatMap[F]): F[Unit] = span("switch.toggle") { switch.toggle }
      override def metadata: SwitchMetadata = switch.metadata
    }
}
