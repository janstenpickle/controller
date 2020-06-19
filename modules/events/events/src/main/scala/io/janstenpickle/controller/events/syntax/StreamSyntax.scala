package io.janstenpickle.controller.events.syntax

import cats.FlatMap
import fs2.Stream
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.Event
import natchez.{Trace, TraceValue}
import cats.syntax.flatMap._
import natchez.TraceValue.StringValue

trait StreamSyntax {
  implicit class EventStreamTraceContext[F[_]: FlatMap, G[_], A](stream: Stream[F, Event[A]])(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ) {
    private def startSpan[B](
      spanName: String,
      headers: Map[String, String],
      source: String,
      fields: Seq[(String, TraceValue)]
    )(fa: F[B]): F[B] =
      liftLower.lift(spanName -> headers)(
        liftLower.lower(spanName -> headers)(trace.put(fields :+ ("event.source" -> StringValue(source)): _*) >> fa)
      )

    def evalMapEventTrace[B](spanName: String, fields: (String, TraceValue)*)(f: Event[A] => F[B]): Stream[F, B] =
      stream.evalMap { event =>
        startSpan(spanName, event.headers, event.source, fields)(f(event))
      }

    def evalMapTrace[B](spanName: String, fields: (String, TraceValue)*)(f: A => F[B]): Stream[F, B] = stream.evalMap {
      event =>
        startSpan(spanName, event.headers, event.source, fields)(f(event.value))
    }
  }
}
