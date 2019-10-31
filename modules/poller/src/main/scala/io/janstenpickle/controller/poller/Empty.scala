package io.janstenpickle.controller.poller

import cats.kernel.Monoid

import scala.collection.compat._

trait Empty[A] {
  def empty: A
}

object Empty extends LowPriorityEmptyInstances {
  def apply[A](implicit empty: Empty[A]): Empty[A] = empty

  def apply[A](a: A): Empty[A] = new Empty[A] {
    override def empty: A = a
  }

  implicit def collection[F[T] <: Iterable[T], A](implicit factory: Factory[A, F[A]]): Empty[F[A]] =
    Empty(factory.newBuilder.result())

  implicit def map[A, B]: Empty[Map[A, B]] = Empty(Map.empty[A, B])
}

trait LowPriorityEmptyInstances {
  implicit def fromMonoid[A](implicit monoid: Monoid[A]): Empty[A] = Empty[A](monoid.empty)
}
