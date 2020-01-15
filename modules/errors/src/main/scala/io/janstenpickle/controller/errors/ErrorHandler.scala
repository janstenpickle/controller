package io.janstenpickle.controller.errors

trait ErrorHandler[F[_]] {
  def handle[A](fa: F[A])(f: Throwable => A): F[A]
  def handleWith[A](fa: F[A])(f: Throwable => F[A]): F[A]
}
