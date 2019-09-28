package io.janstenpickle.controller.model

import shapeless.LowPriority

trait SetEditable[A] {
  def set(a: A)(editable: Boolean): A
}

object SetEditable {
  def apply[A](f: (A, Boolean) => A): SetEditable[A] = new SetEditable[A] {
    override def set(a: A)(editable: Boolean): A = f(a, editable)
  }

  implicit def default[A](implicit lp: LowPriority): SetEditable[A] = new SetEditable[A] {
    override def set(a: A)(editable: Boolean): A = a
  }
}
