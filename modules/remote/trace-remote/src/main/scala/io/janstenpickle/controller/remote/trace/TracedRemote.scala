package io.janstenpickle.controller.remote.trace

import cats.Apply
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.remote.Remote
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue

object TracedRemote {
  def apply[F[_]: Apply, T](remote: Remote[F, T], extraFields: (String, AttributeValue)*)(
    implicit trace: Trace[F]
  ): Remote[F, T] =
    new Remote[F, T] {
      override val name: NonEmptyString = remote.name

      private val fields: Seq[(String, AttributeValue)] = extraFields :+
        ("remote.name" -> StringValue(name.value))

      private def span[A](name: String)(k: F[A]): F[A] =
        trace.span[A](name) { trace.putAll(fields: _*) *> k }

      override def learn: F[Option[T]] = span("remote.learn") { remote.learn }

      override def sendCommand(payload: T): F[Unit] = span("remote.send.command") { remote.sendCommand(payload) }
    }
}
