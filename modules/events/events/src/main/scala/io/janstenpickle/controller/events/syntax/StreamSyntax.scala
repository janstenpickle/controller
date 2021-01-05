package io.janstenpickle.controller.events.syntax

import cats.FlatMap
import cats.effect.BracketThrow
import cats.syntax.flatMap._
import fs2.Stream
import io.janstenpickle.controller.events.Event
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue

trait StreamSyntax {
  implicit class EventStreamTraceContext[F[_]: FlatMap, G[_], A](stream: Stream[F, Event[A]])(
    implicit trace: Trace[F],
    provide: Provide[G, F, Span[G]]
  ) {
    private def startSpan[B](
      spanName: String,
      headers: Map[String, String],
      source: String,
      k: ResourceKleisli[G, (SpanName, Map[String, String]), Span[G]],
      fields: Seq[(String, AttributeValue)],
    )(fa: F[B])(implicit G: BracketThrow[G]): F[B] =
      provide.lift(
        k.run(spanName -> headers)
          .use(provide.provide(trace.putAll(fields :+ ("event.source" -> StringValue(source)): _*) >> fa))
      )

    def evalMapEventTrace[B](
      spanName: String,
      k: ResourceKleisli[G, (SpanName, Map[String, String]), Span[G]],
      fields: (String, AttributeValue)*
    )(f: Event[A] => F[B])(implicit G: BracketThrow[G]): Stream[F, B] =
      stream.evalMap { event =>
        startSpan(spanName, event.headers, event.source, k, fields)(f(event))
      }

    def evalMapTrace[B](
      spanName: String,
      k: ResourceKleisli[G, (SpanName, Map[String, String]), Span[G]],
      fields: (String, AttributeValue)*
    )(f: A => F[B])(implicit G: BracketThrow[G]): Stream[F, B] =
      stream.evalMap { event =>
        startSpan(spanName, event.headers, event.source, k, fields)(f(event.value))
      }
  }
}
