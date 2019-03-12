package io.janstenpickle.controller.api.error

import java.time.Instant

import cats.data.EitherT
import cats.effect.Sync
import cats.syntax.flatMap._
import eu.timepit.refined.types.string.NonEmptyString
import extruder.core.ExtruderErrors
import io.circe.CursorOp
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.switch.hs100.HS100Errors
import io.janstenpickle.controller.`macro`.MacroErrors
import org.slf4j.LoggerFactory
import cats.syntax.functor._
import io.circe
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.SwitchErrors

class ErrorInterpreter[F[_]](implicit F: Sync[F])
    extends MacroErrors[EitherT[F, ControlError, ?]]
    with SwitchErrors[EitherT[F, ControlError, ?]]
    with RemoteControlErrors[EitherT[F, ControlError, ?]]
    with HS100Errors[EitherT[F, ControlError, ?]]
    with PollingSwitchErrors[EitherT[F, ControlError, ?]]
    with ExtruderErrors[EitherT[F, ControlError, ?]] {

  private val log = LoggerFactory.getLogger(getClass)

  private def raise[A](error: ControlError): EitherT[F, ControlError, A] =
    error match {
      case ControlError.Missing(_) => EitherT.leftT[F, A](error)
      case ControlError.Internal(message) => EitherT.left[A](suspendErrors(log.warn(message)).map(_ => error))
    }

  override def missingMacro[A](name: NonEmptyString): EitherT[F, ControlError, A] =
    raise(ControlError.Missing(s"Macro '$name' not found"))
  override def missingRemote[A](name: NonEmptyString): EitherT[F, ControlError, A] =
    raise(ControlError.Missing(s"Remote '$name' not found"))
  override def missingSwitch[A](device: NonEmptyString, name: NonEmptyString): EitherT[F, ControlError, A] =
    raise(ControlError.Missing(s"Switch of type '$device' named '$name' not found"))

  override def decodingFailure[A](name: NonEmptyString, error: circe.Error): EitherT[F, ControlError, A] =
    raise(ControlError.Internal(s"Failed to decode response from smart plug '$name': ${error.getMessage}"))

  override def missingJson[A](name: NonEmptyString, history: List[CursorOp]): EitherT[F, ControlError, A] =
    raise(ControlError.Internal(s"Missing data from smart plug '$name' response at '${history.mkString(",")}'"))

  override def command[A](name: NonEmptyString, errorCode: Int): EitherT[F, ControlError, A] =
    raise(ControlError.Internal(s"Command failed on smart plug '$name' with error code '$errorCode'"))

  override def commandNotFound[A](
    remote: NonEmptyString,
    device: NonEmptyString,
    name: NonEmptyString
  ): EitherT[F, ControlError, A] =
    raise(ControlError.Missing(s"Command '$name' for device '$device' on remote '$remote' not found"))

  override def learnFailure[A](
    remote: NonEmptyString,
    device: NonEmptyString,
    name: NonEmptyString
  ): EitherT[F, ControlError, A] =
    raise(ControlError.Internal(s"Failed to learn command '$name' for device '$device' on remote '$remote'"))

  override def pollError[A](
    switch: NonEmptyString,
    value: State,
    lastUpdated: Long,
    error: Throwable
  ): EitherT[F, ControlError, A] =
    raise(
      ControlError
        .Internal(s"Failed to update switch '$switch' state. Current value: '${value.value}'. Last updated at ${Instant
          .ofEpochMilli(lastUpdated)}. Error message: '${error.getMessage}'")
    )

  override def missing[A](message: String): EitherT[F, ControlError, A] =
    raise(ControlError.Internal(s"Failed to load config data: $message"))

  override def validationFailure[A](message: String): EitherT[F, ControlError, A] =
    raise(ControlError.Internal(s"Failed to validate config data: $message"))

  override def validationException[A](message: String, ex: Throwable): EitherT[F, ControlError, A] =
    validationFailure(message)

  override def fallback[A](
    fa: EitherT[F, ControlError, A]
  )(thunk: => EitherT[F, ControlError, A]): EitherT[F, ControlError, A] =
    EitherT(fa.value.flatMap(_.fold(_ => thunk.value, a => F.pure(Right(a)))))

  override def macroAlreadyExists[A](name: NonEmptyString): EitherT[F, ControlError, A] = ???

  override def learningNotSupported[A](remote: NonEmptyString): EitherT[F, ControlError, A] = ???
}
