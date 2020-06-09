package io.janstenpickle.controller.trace

import cats.Applicative
import cats.effect.Resource
import natchez.{Kernel, Span, TraceValue}

object EmptyTrace {
  def emptySpan[F[_]](implicit F: Applicative[F]): Span[F] = new Span[F] {
    override def put(fields: (String, TraceValue)*): F[Unit] = F.unit
    override def kernel: F[Kernel] = F.pure(Kernel(Map.empty))
    override def span(name: String): Resource[F, Span[F]] = Resource.pure(emptySpan)
  }
}
