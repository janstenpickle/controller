package io.janstenpickle.controller.schedule

import cats.instances.list._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Monad, Parallel}
import io.janstenpickle.controller.schedule.model.Schedule

object CombinedScheduler {
  def apply[F[_]: Parallel](first: Scheduler[F], second: Scheduler[F], others: Scheduler[F]*)(
    implicit F: Monad[F]
  ): Scheduler[F] =
    new Scheduler[F] {
      private def fallback[A](f: Scheduler[F] => F[Option[A]]): F[Option[A]] =
        F.tailRecM[List[Scheduler[F]], Option[A]](List(first, second) ++ others) {
          case h :: t =>
            f(h).map {
              case Some(a) => Right(Some(a))
              case None => Left(t)
            }
          case Nil => F.pure(Right(None))
        }

      override def create(schedule: Schedule): F[Option[String]] = fallback(_.create(schedule))
      override def update(id: String, schedule: Schedule): F[Option[Unit]] = fallback(_.update(id, schedule))
      override def list: F[List[String]] = (List(first, second) ++ others).parFlatTraverse(_.list)
      override def delete(id: String): F[Option[Unit]] = fallback(_.delete(id))
      override def info(id: String): F[Option[Schedule]] = fallback(_.info(id))
    }
}
