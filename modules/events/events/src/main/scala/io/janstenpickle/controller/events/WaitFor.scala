package io.janstenpickle.controller.events

import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Timer}
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
      fiber <- es.subscribe.evalMap(pf.lift(_).sequence).unNone.takeWhile(!_).compile.last.timeout(timeout).start
      a <- fa
      result <- fiber.join
    } yield result.as(a)
}
