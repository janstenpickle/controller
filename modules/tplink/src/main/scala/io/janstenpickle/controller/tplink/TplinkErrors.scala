package io.janstenpickle.controller.tplink

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{CursorOp, Error}

trait TplinkErrors[F[_]] {
  def decodingFailure[A](device: NonEmptyString, error: Error): F[A]
  def tpLinkCommandTimedOut[A](device: NonEmptyString): F[A]
  def missingJson[A](name: NonEmptyString, history: List[CursorOp]): F[A]
  def command[A](name: NonEmptyString, errorCode: Int): F[A]
}