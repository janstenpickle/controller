package io.janstenpickle.controller.poller

import cats.kernel.Monoid

trait Empty[A] {
  def empty: A
}

object Empty {
  def apply[A](implicit empty: Empty[A]): Empty[A] = empty

  def apply[A](a: A): Empty[A] = new Empty[A] {
    override def empty: A = a
  }

  def fromMonoid[A](implicit monoid: Monoid[A]): Empty[A] = Empty[A](monoid.empty)
}
