package io.janstenpickle.controller.events

import cats.effect.kernel.{Outcome, Temporal}
import cats.effect.syntax.spawn._
import cats.effect.syntax.temporal._
import cats.instances.option._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, ApplicativeError}

import scala.concurrent.duration.FiniteDuration

object WaitFor {
  def apply[F[_]: Temporal, A, B](
    es: EventSubscriber[F, A]
  )(fa: F[B], timeout: FiniteDuration)(pf: PartialFunction[A, F[Boolean]]): F[Option[B]] =
    for {
      fiber <- es.subscribe
        .evalMap(pf.lift(_).sequence.map(_.getOrElse(false)))
        .takeWhile(!_)
        .compile
        .drain
        .as(Option(true))
        .timeoutTo(timeout, Applicative[F].pure(None))
        .start
      a <- fa
      result <- fiber.join
      res <- result match {
        case Outcome.Succeeded(fa) => fa
        case Outcome.Errored(e) => ApplicativeError[F, Throwable].raiseError(e)
        case Outcome.Canceled() => Applicative[F].pure(Option.empty[Boolean])
      }
    } yield res.map(_ => a)
}
