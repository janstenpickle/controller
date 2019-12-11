package io.janstenpickle.controller.remote.trace

import cats.Apply
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.remote.Remote
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

object TracedRemote {
  def apply[F[_]: Apply, T](remote: Remote[F, T], extraFields: (String, TraceValue)*)(
    implicit trace: Trace[F]
  ): Remote[F, T] =
    new Remote[F, T] {
      override val name: NonEmptyString = remote.name

      private val fields: Seq[(String, TraceValue)] = extraFields :+
        ("remote.name" -> StringValue(name.value))

      private def span[A](name: String)(k: F[A]): F[A] =
        trace.span[A](name) { trace.put(fields: _*) *> k }

      override def learn: F[Option[T]] = span("remote.learn") { remote.learn }

      override def sendCommand(payload: T): F[Unit] = span("remote.send.command") { remote.sendCommand(payload) }
    }
}
