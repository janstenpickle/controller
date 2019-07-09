package io.janstenpickle.controller.model

trait SetErrors[A] {
  def setErrors(a: A)(errors: List[String]): A
}

object SetErrors {
  def apply[A](f: (A, List[String]) => A): SetErrors[A] = new SetErrors[A] {
    override def setErrors(a: A)(errors: List[String]): A = f(a, errors)
  }
}
