package io.janstenpickle.controller.kodi

import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.errors.ErrorHandler

trait KodiErrors[F[_]] { self: ErrorHandler[F] =>
  def rpcError[A](kodi: NonEmptyString, host: NonEmptyString, port: PortNumber, code: Int, message: String): F[A]
  def missingResult[A](kodi: NonEmptyString, host: NonEmptyString, port: PortNumber): F[A]
}
