package io.janstenpickle.controller.tplink.hs100

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.CursorOp
import io.janstenpickle.controller.tplink.TplinkErrors

trait HS100Errors[F[_]] extends TplinkErrors[F] {
  def missingJson[A](name: NonEmptyString, history: List[CursorOp]): F[A]
  def command[A](name: NonEmptyString, errorCode: Int): F[A]
}
