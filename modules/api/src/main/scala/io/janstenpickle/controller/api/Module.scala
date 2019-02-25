package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.{Concurrent, ContextShift, Timer}
import extruder.cats.effect.EffectValidation
import extruder.core.ValidationErrorsToThrowable
import extruder.data.ValidationErrors
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.remote.Remote
import io.janstenpickle.controller.remote.rm2.Rm2Remote
import io.janstenpickle.controller.switch.hs100.HS100SmartPlug
import io.janstenpickle.controller.store.Store
import io.janstenpickle.controller.store.file.FileStore

import scala.concurrent.ExecutionContext
import fs2.Stream
import io.janstenpickle.controller.api.error.{ControlError, ErrorInterpreter}
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource
import io.janstenpickle.controller.remotecontrol.RemoteControl
import io.janstenpickle.controller.view.View

abstract class Module[F[_]: ContextShift: Timer](implicit F: Concurrent[F]) {
  type ConfigResult[A] = EffectValidation[F, A]

  implicit val errors: ErrorInterpreter[F] = new ErrorInterpreter[F]()

  val config = Configuration.load[ConfigResult]

  val executor = cachedExecutorResource[F]

  def configOrError(result: Either[ValidationErrors, Configuration.Config]): F[Configuration.Config] =
    result.fold(
      errs => F.raiseError(ValidationErrorsToThrowable.defaultValidationErrorsThrowable.convertErrors(errs)),
      F.pure
    )

  def evalControlError[A](fa: EitherT[F, ControlError, A]): Stream[F, A] =
    Stream.eval(fa.value).evalMap[F, A] {
      case Left(err) => F.raiseError(err)
      case Right(a) => F.pure(a)
    }

  def components: Stream[
    F,
    (Configuration.Server, ConfigSource[EitherT[F, ControlError, ?]], View[EitherT[F, ControlError, ?]])
  ] =
    for {
      config <- Stream.eval(config.value).evalMap(configOrError)
      executor <- Stream.resource(cachedExecutorResource[F])
      fileStore <- evalControlError(FileStore[EitherT[F, ControlError, ?]](config.fileStore, executor))
      hs100 = HS100SmartPlug[EitherT[F, ControlError, ?]](config.hs100, executor)
      rm2 <- evalControlError(Rm2Remote[EitherT[F, ControlError, ?]](config.rm2, executor))
      configSource = ExtruderConfigSource[EitherT[F, ControlError, ?]](config.data, executor)
      remoteControl = RemoteControl[EitherT[F, ControlError, ?]](rm2, fileStore)
      view = new View[EitherT[F, ControlError, ?]](
        Map(config.rm2.name -> remoteControl),
        fileStore,
        configSource,
        Map(hs100.name -> hs100)
      )
    } yield (config.server, configSource, view)
}
