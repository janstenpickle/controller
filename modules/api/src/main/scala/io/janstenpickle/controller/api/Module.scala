package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.{Concurrent, ContextShift, Timer}
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.~>
import extruder.cats.effect.EffectValidation
import extruder.core.ValidationErrorsToThrowable
import fs2.Stream
import fs2.concurrent.Topic
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.api.error.{ControlError, ErrorInterpreter}
import io.janstenpickle.controller.api.view.ConfigView
import io.janstenpickle.controller.configsource.extruder._
import io.janstenpickle.controller.configsource.{ActivityConfigSource, ButtonConfigSource, RemoteConfigSource}
import io.janstenpickle.controller.model.CommandPayload
import io.janstenpickle.controller.remote.rm2.Rm2Remote
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControls}
import io.janstenpickle.controller.sonos.SonosComponents
import io.janstenpickle.controller.store.file.{CommandSerde, FileActivityStore, FileMacroStore, FileRemoteCommandStore}
import io.janstenpickle.controller.switch.hs100.HS100SmartPlug
import io.janstenpickle.controller.switch.{Switch, SwitchKey, SwitchProvider, Switches}

import scala.concurrent.ExecutionContext

abstract class Module[F[_]: ContextShift: Timer](implicit F: Concurrent[F]) {
  type ConfigResult[A] = EffectValidation[F, A]
  type ET[A] = EitherT[F, ControlError, A]

  implicit val errors: ErrorInterpreter[F] = new ErrorInterpreter[F]()

  def configOrError(result: ConfigResult[Configuration.Config]): ET[Configuration.Config] =
    EitherT(
      result.value.flatMap(
        _.fold[EitherT[F, ControlError, Configuration.Config]](
          errs =>
            EitherT.liftF[F, ControlError, Configuration.Config](
              F.raiseError(ValidationErrorsToThrowable.defaultValidationErrorsThrowable.convertErrors(errs))
          ),
          EitherT.pure(_)
        ).value
      )
    )

  val translateF: ET ~> F = new (ET ~> F) {
    override def apply[A](fa: ET[A]): F[A] = fa.value.flatMap {
      case Left(err) => F.raiseError(err)
      case Right(a) => F.pure(a)
    }
  }

  implicit val commandPayloadSerde: CommandSerde[ET, CommandPayload] =
    CommandSerde[ET, String].imap(CommandPayload)(_.hexValue)

  def sendUpdate[A](topic: Topic[ET, Boolean], topics: Topic[ET, Boolean]*): A => ET[Unit] =
    _ => (topic :: topics.toList).traverse(_.publish1(true)).void

  def components: Stream[
    ET,
    (
      Configuration.Server,
      Activity[ET],
      Macro[ET],
      RemoteControls[ET],
      Switches[ET],
      ActivityConfigSource[ET],
      ConfigView[ET],
      ExecutionContext,
      UpdateTopics[ET]
    )
  ] =
    for {
      config <- Stream.eval[ET, Configuration.Config](configOrError(Configuration.load[ConfigResult]))
      executor <- Stream.resource(cachedExecutorResource[ET])

      activitiesUpdate <- Stream.eval(Topic[ET, Boolean](false))
      buttonsUpdate <- Stream.eval(Topic[ET, Boolean](false))
      remotesUpdate <- Stream.eval(Topic[ET, Boolean](false))

      configFileSource <- Stream.resource[ET, ConfigFileSource[ET]](
        ConfigFileSource.polling[ET](config.config.file, config.config.pollInterval, executor)
      )
      activityConfig <- Stream.resource[ET, ActivityConfigSource[ET]](
        ExtruderActivityConfigSource.polling[ET](configFileSource, config.config.activity, sendUpdate(activitiesUpdate))
      )
      buttonConfig <- Stream.resource[ET, ButtonConfigSource[ET]](
        ExtruderButtonConfigSource.polling[ET](configFileSource, config.config.button, sendUpdate(buttonsUpdate))
      )
      remoteConfig <- Stream.resource[ET, RemoteConfigSource[ET]](
        ExtruderRemoteConfigSource.polling[ET](configFileSource, config.config.remote, sendUpdate(remotesUpdate))
      )

      activityStore <- Stream.eval(FileActivityStore[ET](config.activityStore, executor))
      macroStore <- Stream.eval(FileMacroStore[ET](config.macroStore, executor))
      commandStore <- Stream.eval(FileRemoteCommandStore[ET, CommandPayload](config.remoteCommandStore, executor))
      rm2 <- Stream.eval(Rm2Remote[ET](config.rm2, executor))
      hs100 <- Stream
        .resource[ET, Switch[ET]](
          HS100SmartPlug
            .polling[ET](config.hs100.config, config.hs100.polling, sendUpdate(buttonsUpdate, remotesUpdate), executor)
        )

      sonosComponents <- Stream.resource[ET, SonosComponents[ET]](
        SonosComponents[ET](config.sonos, sendUpdate(buttonsUpdate, remotesUpdate), executor)
      )

      combinedActivityConfig = ActivityConfigSource.combined[ET](activityConfig, sonosComponents.activityConfig)
      combinedRemoteConfig = RemoteConfigSource.combined[ET](sonosComponents.remoteConfig, remoteConfig)
      remoteControls = RemoteControls[ET](
        Map(
          config.rm2.name -> RemoteControl[ET, CommandPayload](rm2, commandStore),
          config.sonos.remote -> sonosComponents.remote
        )
      )

      combinedSwitchProvider = SwitchProvider
        .combined[ET](
          SwitchProvider[ET](Map(SwitchKey(hs100.device, config.hs100.config.name) -> hs100)),
          sonosComponents.switches
        )

      switches = new Switches[ET](combinedSwitchProvider)
      macros = new Macro[ET](macroStore, remoteControls, switches)
      activity = new Activity[ET](activityStore, macros, sendUpdate(activitiesUpdate))
      configView = new ConfigView(
        combinedActivityConfig,
        buttonConfig,
        combinedRemoteConfig,
        macroStore,
        activityStore,
        switches
      )
    } yield
      (
        config.server,
        activity,
        macros,
        remoteControls,
        switches,
        combinedActivityConfig,
        configView,
        executor,
        UpdateTopics(activitiesUpdate, buttonsUpdate, remotesUpdate)
      )
}
