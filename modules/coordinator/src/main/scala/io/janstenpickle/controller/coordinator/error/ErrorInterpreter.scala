package io.janstenpickle.controller.coordinator.error

import cats.Apply
import cats.data.NonEmptyList
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import extruder.core.ExtruderErrors
import io.chrisdavenport.log4cats.Logger
import io.janstenpickle.controller.`macro`.MacroErrors
import io.janstenpickle.controller.api.service.ConfigServiceErrors
import io.janstenpickle.controller.api.validation.ConfigValidation
import io.janstenpickle.controller.context.ContextErrors
import io.janstenpickle.controller.http4s.error
import io.janstenpickle.controller.http4s.error.ControlError
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.switch.SwitchErrors

class ErrorInterpreter[F[_]: Apply](
  implicit
  fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError],
  logger: Logger[F]
) extends error.BaseErrorInterpreter[F]
    with MacroErrors[F]
    with SwitchErrors[F]
    with RemoteControlErrors[F]
    with ContextErrors[F]
    with ExtruderErrors[F]
    with ConfigServiceErrors[F] {

  override def missingMacro[A](name: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Macro '$name' not found"))
  override def missingRemote[A](name: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Remote '$name' not found"))
  override def missingSwitch[A](device: NonEmptyString, name: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Switch of type '$device' named '$name' not found"))

  override def commandNotFound[A](remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Command '$name' for device '$device' on remote '$remote' not found"))

  override def learnFailure[A](remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[A] =
    raise(ControlError.Internal(s"Failed to learn command '$name' for device '$device' on remote '$remote'"))

  override def missing[A](message: String): F[A] =
    raise(ControlError.Internal(s"Failed to load config data: $message"))

  override def validationFailure[A](message: String): F[A] =
    raise(ControlError.Internal(s"Failed to validate config data: $message"))

  override def validationException[A](message: String, ex: Throwable): F[A] =
    validationFailure(message)

  override def fallback[A](fa: F[A])(thunk: => F[A]): F[A] =
    ah.handleWith(fa)(_ => thunk)

  override def macroAlreadyExists[A](name: NonEmptyString): F[A] =
    raise(ControlError.InvalidInput(s"Macro '$name' already exists"))

  override def learningNotSupported[A](remote: NonEmptyString): F[A] =
    raise(ControlError.InvalidInput(s"Remote '$remote' does not support learning mode"))

  override def configValidationFailed[A](failures: NonEmptyList[ConfigValidation.ValidationFailure]): F[A] =
    raise(ControlError.InvalidInput(s"Failed to validate configuration\n ${failures.toList.mkString("\n")}"))

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

  override def remoteAlreadyExists[A](remote: NonEmptyString): F[A] =
    raise(ControlError.InvalidInput(s"Remote '$remote' already exists"))

  override def activityAlreadyExists[A](room: NonEmptyString, name: NonEmptyString): F[A] =
    raise(ControlError.InvalidInput(s"Activity '$name' in room '$room' already exists"))

  override def buttonAlreadyExists[A](button: NonEmptyString): F[A] =
    raise(ControlError.InvalidInput(s"Button '$button' already exists"))

  private def kodiString(kodi: NonEmptyString, host: NonEmptyString, port: PortNumber): String =
    s"Kodi '${kodi.value}' at '${host.value}:${port.value}'"

  override def activityNotSet[A](room: Room): F[A] =
    raise(ControlError.Missing(s"Activity not currently set in room '$room"))

  override def activityNotPresent[A](room: Room, activity: NonEmptyString): F[A] =
    raise(ControlError.Missing(s"Current activity '$activity' in room '$room' is not present in configuration"))
}

object ErrorInterpreter {
  implicit def default[F[_]: Apply: Logger](
    implicit fr: FunctorRaise[F, ControlError],
    ah: ApplicativeHandle[F, ControlError]
  ): ErrorInterpreter[F] = new ErrorInterpreter[F]()
}
