package io.janstenpickle.controller.switch.hs100

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{CursorOp, Error}

trait HS100Errors[F[_]] {
  def decodingFailure[A](name: NonEmptyString, error: Error): F[A]
  def missingJson[A](name: NonEmptyString, history: List[CursorOp]): F[A]
  def command[A](name: NonEmptyString, errorCode: Int): F[A]
}
