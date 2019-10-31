package io.janstenpickle.controller.kodi

import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString

trait KodiErrors[F[_]] {
  def rpcError[A](kodi: NonEmptyString, host: NonEmptyString, port: PortNumber, code: Int, message: String): F[A]
  def missingResult[A](kodi: NonEmptyString, host: NonEmptyString, port: PortNumber): F[A]
}
