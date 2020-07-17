package io.janstenpickle.controller.events

import cats.Applicative
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Sync, Timer}
import cats.instances.option._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.concurrent.duration.FiniteDuration

object WaitFor {
  def apply[F[_]: Concurrent: Timer, A, B](
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
    } yield result.as(a)
}
