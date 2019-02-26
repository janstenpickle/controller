package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.{Concurrent, ContextShift, Timer}
import extruder.cats.effect.EffectValidation
import extruder.core.ValidationErrorsToThrowable
import extruder.data.ValidationErrors
import fs2.Stream
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.api.error.{ControlError, ErrorInterpreter}
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource
import io.janstenpickle.controller.model.CommandPayload
import io.janstenpickle.controller.remote.rm2.Rm2Remote
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControls}
import io.janstenpickle.controller.store.file.{CommandSerde, FileActivityStore, FileMacroStore, FileRemoteCommandStore}
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switch.hs100.HS100SmartPlug

abstract class Module[F[_]: ContextShift: Timer](implicit F: Concurrent[F]) {
  type ConfigResult[A] = EffectValidation[F, A]
  type ET[A] = EitherT[F, ControlError, A]

  implicit val errors: ErrorInterpreter[F] = new ErrorInterpreter[F]()

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

  implicit val commandPayloadSerde: CommandSerde[ET, CommandPayload] =
    CommandSerde[ET, String].imap(CommandPayload)(_.hexValue)

  def components: Stream[
    F,
    (
      Configuration.Server,
      Activity[ET],
      ConfigSource[ET],
      Macro[ET],
      RemoteControls[ET],
      Switches[ET]
    )
  ] =
    for {
      config <- Stream.eval(Configuration.load[ConfigResult].value).evalMap(configOrError)
      executor <- Stream.resource(cachedExecutorResource[F])
      activityStore <- evalControlError(FileActivityStore[ET](config.activityStore, executor))
      macroStore <- evalControlError(FileMacroStore[ET](config.macroStore, executor))
      commandStore <- evalControlError(
        FileRemoteCommandStore[ET, CommandPayload](config.remoteCommandStore, executor)
      )
      rm2 <- evalControlError(Rm2Remote[ET](config.rm2, executor))
      configSource = ExtruderConfigSource[ET](config.data, executor)
      remoteControls = new RemoteControls[ET](
        Map(config.rm2.name -> RemoteControl[ET, CommandPayload](rm2, commandStore))
      )
      switches = new Switches[ET](
        Map(config.hs100.name -> HS100SmartPlug[ET](config.hs100, executor))
      )
      macros = new Macro[ET](macroStore, remoteControls, switches)
      activity = new Activity[ET](activityStore, macros)
    } yield (config.server, activity, configSource, macros, remoteControls, switches)
}
