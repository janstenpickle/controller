package io.janstenpickle.controller.trace

import cats.{Applicative, Monad, Parallel}
import cats.effect.Resource
import cats.kernel.{Monoid, Semigroup}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.instances.unit._
import natchez.{EntryPoint, Kernel, Span, TraceValue}

trait Instances extends LowPriorityInstances {

  implicit def entryPointSemigroup[F[_]: Monad]: Semigroup[EntryPoint[F]] = new Semigroup[EntryPoint[F]] {
    override def combine(x: EntryPoint[F], y: EntryPoint[F]): EntryPoint[F] = new EntryPoint[F] {
      override def root(name: String): Resource[F, Span[F]] =
        x.root(name) |+| y.root(name)

      override def continue(name: String, kernel: Kernel): Resource[F, Span[F]] =
        x.continue(name, kernel) |+| y.continue(name, kernel)

      override def continueOrElseRoot(name: String, kernel: Kernel): Resource[F, Span[F]] =
        x.continueOrElseRoot(name, kernel) |+| y.continueOrElseRoot(name, kernel)
    }
  }

  implicit def spanSemigroupFromParallel[F[_]: Applicative: Parallel]: Semigroup[Span[F]] = new Semigroup[Span[F]] {
    override def combine(x: Span[F], y: Span[F]): Span[F] = new Span[F] {
      override def put(fields: (String, TraceValue)*): F[Unit] =
        Parallel.parMap2(x.put(fields: _*), y.put(fields: _*))(_ |+| _)

      override def kernel: F[Kernel] = Parallel.parMap2(x.kernel, y.kernel)(_ |+| _)

      override def span(name: String): Resource[F, Span[F]] =
        for {
          xs <- x.span(name)
          ys <- y.span(name)
        } yield combine(xs, ys)
    }
  }
}

trait LowPriorityInstances {
  implicit def spanSemigroup[F[_]: Monad]: Semigroup[Span[F]] = new Semigroup[Span[F]] {
    override def combine(x: Span[F], y: Span[F]): Span[F] = new Span[F] {
      override def put(fields: (String, TraceValue)*): F[Unit] =
        x.put(fields: _*) *> y.put(fields: _*)

      override def kernel: F[Kernel] =
        for {
          xk <- x.kernel
          yk <- y.kernel
        } yield xk |+| yk

      override def span(name: String): Resource[F, Span[F]] =
        for {
          xs <- x.span(name)
          ys <- y.span(name)
        } yield combine(xs, ys)
    }
  }

  implicit val kernelMonoid: Monoid[Kernel] = new Monoid[Kernel] {
    override def empty: Kernel = Kernel(Map.empty)
    override def combine(x: Kernel, y: Kernel): Kernel = Kernel(x.toHeaders ++ y.toHeaders)
  }
}
