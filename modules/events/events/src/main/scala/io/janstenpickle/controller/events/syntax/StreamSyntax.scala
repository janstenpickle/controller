package io.janstenpickle.controller.events.syntax

import cats.FlatMap
import cats.syntax.flatMap._
import fs2.Stream
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.Event
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue

trait StreamSyntax {
  implicit class EventStreamTraceContext[F[_]: FlatMap, G[_], A](stream: Stream[F, Event[A]])(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ) {
    private def startSpan[B](
      spanName: String,
      headers: Map[String, String],
      source: String,
      fields: Seq[(String, AttributeValue)]
    )(fa: F[B]): F[B] =
      liftLower.lift(spanName -> headers)(
        liftLower.lower(spanName -> headers)(trace.putAll(fields :+ ("event.source" -> StringValue(source)): _*) >> fa)
      )

    def evalMapEventTrace[B](spanName: String, fields: (String, AttributeValue)*)(f: Event[A] => F[B]): Stream[F, B] =
      stream.evalMap { event =>
        startSpan(spanName, event.headers, event.source, fields)(f(event))
      }

    def evalMapTrace[B](spanName: String, fields: (String, AttributeValue)*)(f: A => F[B]): Stream[F, B] =
      stream.evalMap { event =>
        startSpan(spanName, event.headers, event.source, fields)(f(event.value))
      }
  }
}
