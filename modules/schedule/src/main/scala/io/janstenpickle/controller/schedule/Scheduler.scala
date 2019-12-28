package io.janstenpickle.controller.schedule

import cats.{Monad, Monoid, Parallel}
import io.janstenpickle.controller.schedule.model.Schedule

trait Scheduler[F[_]] {
  def create(schedule: Schedule): F[Option[String]]
  def update(id: String, schedule: Schedule): F[Option[Unit]]
  def delete(id: String): F[Option[Unit]]
  def info(id: String): F[Option[Schedule]]
  def list: F[List[String]]
}

object Scheduler {
  implicit def schedulerMonoid[F[_]: Parallel](implicit F: Monad[F]): Monoid[Scheduler[F]] = new Monoid[Scheduler[F]] {
    override def empty: Scheduler[F] = new Scheduler[F] {
      override def create(schedule: Schedule): F[Option[String]] = F.pure(None)

      override def update(id: String, schedule: Schedule): F[Option[Unit]] = F.pure(None)

      override def delete(id: String): F[Option[Unit]] = F.pure(None)

      override def info(id: String): F[Option[Schedule]] = F.pure(None)

      override def list: F[List[String]] = F.pure(List.empty)
    }

    override def combine(x: Scheduler[F], y: Scheduler[F]): Scheduler[F] = CombinedScheduler[F](x, y)

    override def combineAll(as: TraversableOnce[Scheduler[F]]): Scheduler[F] = as.toList match {
      case Nil => empty
      case x :: Nil => x
      case x :: y :: xs => CombinedScheduler[F](x, y, xs: _*)
    }
  }
}
