package io.janstenpickle.trace

import java.util.concurrent.TimeUnit

import cats.effect.concurrent.Ref
import cats.effect.{Clock, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.janstenpickle.trace.model._

case class RefSpan[F[_]: Sync: Clock] private (
  context: SpanContext,
  name: String,
  kind: SpanKind,
  start: Long,
  attributes: Ref[F, Map[String, TraceValue]],
  completer: SpanCompleter[F]
) {

  def put(key: String, value: TraceValue): F[Unit] = attributes.update(_ + (key -> value))
  def putAll(fields: (String, TraceValue)*): F[Unit] = attributes.update(_ ++ fields)

  def end(status: SpanStatus): F[Unit] =
    for {
      now <- Clock[F].realTime(TimeUnit.MICROSECONDS)
      attrs <- attributes.get
      completed = CompletedSpan(context, name, kind, start, now, attrs, status)
      _ <- completer.complete(completed)
    } yield ()

}

object RefSpan {
  def child[F[_]: Sync: Clock](
    name: String,
    parent: SpanContext,
    kind: SpanKind,
    completer: SpanCompleter[F]
  ): F[RefSpan[F]] =
    for {
      context <- SpanContext.child[F](parent)
      attributesRef <- Ref.of[F, Map[String, TraceValue]](Map.empty)
      now <- Clock[F].realTime(TimeUnit.MICROSECONDS)
    } yield new RefSpan[F](context, name, kind, now, attributesRef, completer)

  def root[F[_]: Sync: Clock](name: String, kind: SpanKind, completer: SpanCompleter[F]): F[RefSpan[F]] =
    for {
      context <- SpanContext.root[F]
      attributesRef <- Ref.of[F, Map[String, TraceValue]](Map.empty)
      now <- Clock[F].realTime(TimeUnit.MICROSECONDS)
    } yield RefSpan[F](context, name, kind, now, attributesRef, completer)
}
