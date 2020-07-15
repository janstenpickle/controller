package io.janstenpickle.controller.trace

import cats.Functor
import cats.data.EitherT
import io.janstenpickle.trace4cats.ToHeaders
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanKind, SpanStatus}

trait Instances {

  implicit def eitherTTrace[F[_]: Functor, E](implicit trace: Trace[F]): Trace[EitherT[F, E, *]] =
    new Trace[EitherT[F, E, *]] {
      override def put(key: String, value: AttributeValue): EitherT[F, E, Unit] = EitherT.liftF(trace.put(key, value))

      override def putAll(fields: (String, AttributeValue)*): EitherT[F, E, Unit] =
        EitherT.liftF(trace.putAll(fields: _*))

      override def span[A](name: String, kind: SpanKind)(fa: EitherT[F, E, A]): EitherT[F, E, A] =
        EitherT(trace.span(name, kind)(fa.value))

      override def headers(toHeaders: ToHeaders): EitherT[F, E, Map[String, String]] =
        EitherT.liftF(trace.headers(toHeaders))

      override def setStatus(status: SpanStatus): EitherT[F, E, Unit] =
        EitherT.liftF(trace.setStatus(status))
    }
}
