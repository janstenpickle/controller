package io.janstenpickle.controller.events.syntax

import cats.FlatMap
import cats.effect.MonadCancelThrow
import cats.syntax.flatMap._
import fs2.Stream
import io.janstenpickle.controller.events.Event
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue
import org.typelevel.ci.CIString

trait StreamSyntax {
  implicit class EventStreamTraceContext[F[_]: FlatMap, G[_], A](stream: Stream[F, Event[A]])(
    implicit trace: Trace[F],
    provide: Provide[G, F, Span[G]]
  ) {
    private def startSpan[B](
      spanName: String,
      headers: Map[CIString, String],
      source: String,
      k: ResourceKleisli[G, (SpanName, Map[CIString, String]), Span[G]],
      fields: Seq[(String, AttributeValue)],
    )(fa: F[B])(implicit G: MonadCancelThrow[G]): F[B] =
      provide.lift(
        k.run(spanName -> headers)
          .use(provide.provide(trace.putAll(fields :+ ("event.source" -> StringValue(source)): _*) >> fa))
      )

    def evalMapEventTrace[B](
      spanName: String,
      k: ResourceKleisli[G, (SpanName, Map[CIString, String]), Span[G]],
      fields: (String, AttributeValue)*
    )(f: Event[A] => F[B])(implicit G: MonadCancelThrow[G]): Stream[F, B] =
      stream.evalMap { event =>
        startSpan(spanName, event.headers, event.source, k, fields)(f(event))
      }

    def evalMapTrace[B](
      spanName: String,
      k: ResourceKleisli[G, (SpanName, Map[CIString, String]), Span[G]],
      fields: (String, AttributeValue)*
    )(f: A => F[B])(implicit G: MonadCancelThrow[G]): Stream[F, B] =
      stream.evalMap { event =>
        startSpan(spanName, event.headers, event.source, k, fields)(f(event.value))
      }
  }
}
