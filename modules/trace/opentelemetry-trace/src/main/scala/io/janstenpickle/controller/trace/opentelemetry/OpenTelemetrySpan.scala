package io.janstenpickle.controller.trace.opentelemetry

import cats.effect.{ExitCase, Resource, Sync}
import ExitCase._
import io.opentelemetry.trace.Tracer
import natchez.{Fields, Kernel, Span, TraceValue}
import cats.implicits._
import io.grpc.Context
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.common.AttributeValue
import io.opentelemetry.context.ContextUtils
import io.opentelemetry.context.propagation.HttpTextFormat.{Getter, Setter}
import natchez.TraceValue._

import scala.collection.mutable

private[opentelemetry] final case class OpenTelemetrySpan[F[_]: Sync](tracer: Tracer, span: io.opentelemetry.trace.Span)
    extends Span[F] {
  import OpenTelemetrySpan._

  override def put(fields: (String, TraceValue)*): F[Unit] =
    fields.toList.traverse_ {
      case (k, StringValue(v)) =>
        val safeString =
          if (v == null) "null" else v
        Sync[F].delay(span.setAttribute(k, AttributeValue.stringAttributeValue(safeString)))
      case (k, NumberValue(v)) =>
        Sync[F].delay(span.setAttribute(k, AttributeValue.doubleAttributeValue(v.doubleValue())))
      case (k, BooleanValue(v)) =>
        Sync[F].delay(span.setAttribute(k, AttributeValue.booleanAttributeValue(v)))
    }

  override def kernel: F[Kernel] =
    Sync[F].delay {
      val headers: mutable.Map[String, String] = mutable.Map.empty[String, String]
      OpenTelemetry.getPropagators.getHttpTextFormat.inject(Context.current(), null, spanContextSetter)
      Kernel(headers.toMap)
    }

  override def span(name: String): Resource[F, Span[F]] =
    Resource.makeCase(OpenTelemetrySpan.child(this, name))(OpenTelemetrySpan.finish).widen
}

object OpenTelemetrySpan {
  private val spanContextSetter = new Setter[mutable.Map[String, String]] {
    override def set(carrier: mutable.Map[String, String], key: String, value: String): Unit = {
      carrier.put(key, value)
      ()
    }
  }

  private val spanContextGetter: Getter[Kernel] = new Getter[Kernel] {
    override def get(carrier: Kernel, key: String): String =
      carrier.toHeaders(key)
  }

  def finish[F[_]: Sync]: (OpenTelemetrySpan[F], ExitCase[Throwable]) => F[Unit] = { (outer, exitCase) =>
    for {
      // collect error details, if any
      _ <- exitCase.some
        .collect {
          case ExitCase.Error(t: Fields) => t.fields.toList
        }
        .traverse(outer.put)
      _ <- Sync[F].delay {
        exitCase match {
          case Completed => outer.span.setStatus(io.opentelemetry.trace.Status.OK)
          case Canceled => outer.span.setStatus(io.opentelemetry.trace.Status.CANCELLED)
          case ExitCase.Error(ex) =>
            outer.put(("error.msg", ex.getMessage), ("error.stack", ex.getStackTrace.mkString("\n")))
            outer.span.setStatus(io.opentelemetry.trace.Status.INTERNAL.withDescription(ex.getMessage))
        }
      }
      _ <- Sync[F].delay(outer.span.end())
    } yield ()
  }

  def child[F[_]: Sync](parent: OpenTelemetrySpan[F], name: String): F[OpenTelemetrySpan[F]] =
    Sync[F]
      .delay(
        parent.tracer
          .spanBuilder(name)
          .setParent(parent.span)
          .startSpan()
      )
      .map(OpenTelemetrySpan(parent.tracer, _))

  def root[F[_]: Sync](tracer: Tracer, name: String): F[OpenTelemetrySpan[F]] =
    Sync[F]
      .delay(
        tracer
          .spanBuilder(name)
          .startSpan()
      )
      .map(OpenTelemetrySpan(tracer, _))

  def fromKernel[F[_]: Sync](tracer: Tracer, name: String, kernel: Kernel): F[OpenTelemetrySpan[F]] =
    Sync[F]
      .delay {
        val ctx = OpenTelemetry.getPropagators.getHttpTextFormat
          .extract(Context.current(), kernel, spanContextGetter)

        ContextUtils.withScopedContext(ctx)

        tracer
          .spanBuilder(name)
          .setSpanKind(io.opentelemetry.trace.Span.Kind.SERVER)
          .startSpan()
      }
      .map(OpenTelemetrySpan(tracer, _))

  def fromKernelOrElseRoot[F[_]](tracer: Tracer, name: String, kernel: Kernel)(
    implicit ev: Sync[F]
  ): F[OpenTelemetrySpan[F]] =
    fromKernel(tracer, name, kernel).recoverWith {
      case _: NoSuchElementException =>
        root(tracer, name) // means headers are incomplete or invalid
      case _: NullPointerException =>
        root(tracer, name) // means headers are incomplete or invalid
    }
}
