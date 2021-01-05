package io.janstenpickle.controller.tplink

import java.time.Instant

import cats.Apply
import cats.data.NonEmptyList
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import eu.timepit.refined.types.string.NonEmptyString
import io.chrisdavenport.log4cats.Logger
import io.circe
import io.circe.CursorOp
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.`macro`.MacroErrors
import io.janstenpickle.controller.api.service.ConfigServiceErrors
import io.janstenpickle.controller.api.validation.ConfigValidation
import io.janstenpickle.controller.http4s.error
import io.janstenpickle.controller.http4s.error.ControlError
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.switch.SwitchErrors
import io.janstenpickle.controller.tplink.device.TplinkDeviceErrors

class ErrorInterpreter[F[_]: Apply](
  implicit
  fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError],
  logger: Logger[F]
) extends error.BaseErrorInterpreter[F]
    with RemoteControlErrors[F]
    with SwitchErrors[F]
    with MacroErrors[F]
    with TplinkDeviceErrors[F]
    with PollingSwitchErrors[F]
    with ConfigServiceErrors[F] {
  override def commandNotFound[A](remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Command '$name' for device '$device' on remote '$remote' not found"))

  override def learnFailure[A](remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[A] =
    raise(ControlError.Internal(s"Failed to learn command '$name' for device '$device' on remote '$remote'"))

  override def missingRemote[A](name: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Remote '$name' not found"))

  override def learningNotSupported[A](remote: NonEmptyString): F[A] =
    raise(ControlError.InvalidInput(s"Remote '$remote' does not support learning mode"))

  override def missingSwitch[A](device: NonEmptyString, name: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Switch of type '$device' named '$name' not found"))

  override def missingMacro[A](name: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Macro '$name' not found"))

  override def macroAlreadyExists[A](name: NonEmptyString): F[A] =
    raise(ControlError.InvalidInput(s"Macro '$name' already exists"))

  override def configValidationFailed[A](failures: NonEmptyList[ConfigValidation.ValidationFailure]): F[A] =
    raise(ControlError.InvalidInput(s"Failed to validate configuration\n ${failures.toList.mkString("\n")}"))

  override def remoteAlreadyExists[A](remote: NonEmptyString): F[A] =
    raise(ControlError.InvalidInput(s"Remote '$remote' already exists"))

  override def remoteMissing[A](remote: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Remote '$remote' not found"))

  override def buttonMissing[A](button: String): F[A] =
    raise(ControlError.Missing(s"Button '$button' not found"))

  override def activityMissing[A](activity: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Activity '$activity' not found"))

  override def activityInUse[A](activity: NonEmptyString, remotes: List[NonEmptyString]): F[A] =
    raise(
      ControlError.InvalidInput(
        s"Cannot delete activity '$activity' because it is use in the following remotes ${remotes.mkString(",")}"
      )
    )

  override def activityAlreadyExists[A](room: NonEmptyString, name: NonEmptyString): F[A] =
    raise(ControlError.InvalidInput(s"Activity '$name' in room '$room' already exists"))

  override def buttonAlreadyExists[A](button: NonEmptyString): F[A] =
    raise(ControlError.InvalidInput(s"Button '$button' already exists"))

  override def pollError[A](switch: NonEmptyString, value: State, lastUpdated: Long, error: Throwable): F[A] =
    raise(
      ControlError
        .Internal(s"Failed to update switch '$switch' state. Current value: '${value.value}'. Last updated at ${Instant
          .ofEpochMilli(lastUpdated)}. Error message: '${error.getMessage}'")
    )

  override def decodingFailure[A](device: NonEmptyString, error: circe.Error): F[A] =
    raise(ControlError.Internal(s"Failed to decode response from smart plug '$device': ${error.getMessage}"))

  override def missingJson[A](name: NonEmptyString, history: List[CursorOp]): F[A] =
    raise(ControlError.Internal(s"Missing data from smart plug '$name' response at '${history.mkString(",")}'"))

  override def command[A](name: NonEmptyString, errorCode: Int): F[A] =
    raise(ControlError.Internal(s"Command failed on smart plug '$name' with error code '$errorCode'"))

  override def tpLinkCommandTimedOut[A](device: NonEmptyString): F[A] =
    raise(ControlError.Internal(s"TP Link command timed out to device '${device.value}''"))
}

object ErrorInterpreter {
  implicit def default[F[_]: Apply: Logger](
    implicit fr: FunctorRaise[F, ControlError],
    ah: ApplicativeHandle[F, ControlError]
  ): ErrorInterpreter[F] = new ErrorInterpreter[F]()
}
