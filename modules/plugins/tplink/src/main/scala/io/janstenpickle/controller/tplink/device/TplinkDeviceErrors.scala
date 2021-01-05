package io.janstenpickle.controller.tplink.device

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.CursorOp
import io.janstenpickle.controller.errors.ErrorHandler
import io.janstenpickle.controller.tplink.TplinkErrors

trait TplinkDeviceErrors[F[_]] extends TplinkErrors[F] {
  def missingJson[A](name: NonEmptyString, history: List[CursorOp]): F[A]
  def command[A](name: NonEmptyString, errorCode: Int): F[A]
}
