package io.janstenpickle.controller.trace.opentelemetry

import cats.effect.{Resource, Sync}
import io.opentelemetry.trace.Tracer
import natchez.{EntryPoint, Kernel, Span}
import cats.syntax.functor._

object OpenTelemetryTracer {
  def entryPoint[F[_]: Sync](tracer: Tracer): EntryPoint[F] = new EntryPoint[F] {
    override def continue(name: String, kernel: Kernel): Resource[F, Span[F]] =
      Resource.makeCase(OpenTelemetrySpan.fromKernel(tracer, name, kernel))(OpenTelemetrySpan.finish).widen

    override def root(name: String): Resource[F, Span[F]] =
      Resource.makeCase(OpenTelemetrySpan.root(tracer, name))(OpenTelemetrySpan.finish).widen

    override def continueOrElseRoot(name: String, kernel: Kernel): Resource[F, Span[F]] =
      Resource.makeCase(OpenTelemetrySpan.fromKernelOrElseRoot(tracer, name, kernel))(OpenTelemetrySpan.finish).widen
  }
}
