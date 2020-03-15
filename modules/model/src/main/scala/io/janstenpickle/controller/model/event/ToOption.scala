package io.janstenpickle.controller.model.event

trait ToOption[A] {
  def toOption(a: A): Option[A] = Option(a)
}

object ToOption {
  implicit def apply[A](implicit toOption: ToOption[A]): ToOption[A] = implicitly

  def instance[A](f: A => Option[A]): ToOption[A] = new ToOption[A] {
    override def toOption(a: A): Option[A] = f(a)
  }

  def some[A]: ToOption[A] = new ToOption[A] {
    override def toOption(a: A): Option[A] = Some(a)
  }

  def none[A]: ToOption[A] = new ToOption[A] {
    override def toOption(a: A): Option[A] = None
  }
}
