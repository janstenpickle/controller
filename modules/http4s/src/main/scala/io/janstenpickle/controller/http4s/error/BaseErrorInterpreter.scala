package io.janstenpickle.controller.http4s.error

import cats.Apply
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import io.chrisdavenport.log4cats.Logger
import cats.syntax.apply._
import io.janstenpickle.controller.errors.ErrorHandler

abstract class BaseErrorInterpreter[F[_]: Apply](
  implicit
  fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError],
  logger: Logger[F]
) extends ErrorHandler[F] {
  protected def raise[A](error: ControlError): F[A] =
    error match {
      case ControlError.InvalidInput(_) => fr.raise(error)
      case ControlError.Missing(_) => fr.raise(error)
      case ControlError.Internal(message) => logger.warn(message) *> fr.raise(error)
      case e @ ControlError.Combined(_, _) if e.isSevere => logger.warn(e.message) *> fr.raise(error)
      case ControlError.Combined(_, _) => fr.raise(error)
    }

  override def handle[A](fa: F[A])(f: Throwable => A): F[A] = ah.handle(fa)(f)
  override def handleWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = ah.handleWith(fa)(f)

}
