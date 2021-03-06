package io.janstenpickle.controller.events.syntax

import cats.Applicative
import cats.effect.{Concurrent, Timer}
import io.janstenpickle.controller.events.{EventSubscriber, WaitFor}

import scala.concurrent.duration.FiniteDuration

trait EventSubscriberSyntax {
  implicit class WaitForSyntax[F[_], A](es: EventSubscriber[F, A]) {
    def waitForF[B](fa: F[B], timeout: FiniteDuration)(
      pf: PartialFunction[A, F[Boolean]]
    )(implicit F: Concurrent[F], timer: Timer[F]): F[Option[B]] = WaitFor(es)(fa, timeout)(pf)

    def waitFor[B](fa: F[B], timeout: FiniteDuration)(
      pf: PartialFunction[A, Boolean]
    )(implicit F: Concurrent[F], timer: Timer[F]): F[Option[B]] =
      WaitFor(es)(fa, timeout)(pf.andThen(Applicative[F].pure(_)))
  }
}
