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
import io.janstenpickle.controller.broadlink.remote.RmRemoteControls
import io.janstenpickle.controller.broadlink.switch.SpSwitchProvider
import io.janstenpickle.controller.configsource.extruder._
import io.janstenpickle.controller.configsource.{
  ActivityConfigSource,
  ButtonConfigSource,
  RemoteConfigSource,
  VirtualSwitchConfigSource
}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.CommandPayload
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.sonos.SonosComponents
import io.janstenpickle.controller.stats.{Stats, StatsStream}
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.store.file._
import io.janstenpickle.controller.switch.hs100.HS100SwitchProvider
import io.janstenpickle.controller.switch.virtual.{SwitchDependentStore, SwitchesForRemote}
import io.janstenpickle.controller.switch.{SwitchProvider, Switches}
import io.prometheus.client.CollectorRegistry

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

  def notifyUpdate[A](topic: Topic[ET, Boolean], topics: Topic[ET, Boolean]*): A => ET[Unit] =
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
      UpdateTopics[ET],
      CollectorRegistry,
      Stream[F, Stats]
    )
  ] =
    for {
      config <- Stream.eval[ET, Configuration.Config](configOrError(Configuration.load[ConfigResult]))
      executor <- Stream.resource(cachedExecutorResource[ET])
      registry <- Stream[ET, CollectorRegistry](new CollectorRegistry())

      activitiesUpdate <- Stream.eval(Topic[ET, Boolean](false))
      buttonsUpdate <- Stream.eval(Topic[ET, Boolean](false))
      remotesUpdate <- Stream.eval(Topic[ET, Boolean](false))
      roomsUpdate <- Stream.eval(Topic[ET, Boolean](false))
      statsSwitchUpdate <- Stream.eval(Topic[ET, Boolean](false))
      statsConfigUpdate <- Stream.eval(Topic[ET, Boolean](false))

      configFileSource <- Stream.resource[ET, ConfigFileSource[ET]](
        ConfigFileSource.polling[ET](config.config.file, config.config.pollInterval, executor)
      )
      activityConfig <- Stream.resource[ET, ActivityConfigSource[ET]](
        ExtruderActivityConfigSource
          .polling[ET](
            configFileSource,
            config.config.activity,
            notifyUpdate(activitiesUpdate, roomsUpdate, statsConfigUpdate)
          )
      )
      buttonConfig <- Stream.resource[ET, ButtonConfigSource[ET]](
        ExtruderButtonConfigSource
          .polling[ET](
            configFileSource,
            config.config.button,
            notifyUpdate(buttonsUpdate, roomsUpdate, statsConfigUpdate)
          )
      )
      remoteConfig <- Stream.resource[ET, RemoteConfigSource[ET]](
        ExtruderRemoteConfigSource
          .polling[ET](
            configFileSource,
            config.config.remote,
            notifyUpdate(remotesUpdate, roomsUpdate, statsConfigUpdate)
          )
      )
      virtualSwitchConfig <- Stream.resource[ET, VirtualSwitchConfigSource[ET]](
        ExtruderVirtualSwitchConfigSource
          .polling[ET](
            configFileSource,
            config.config.virtualSwitch,
            notifyUpdate(remotesUpdate, roomsUpdate, statsSwitchUpdate)
          )
      )

      activityStore <- Stream.eval(FileActivityStore[ET](config.stores.activityStore, executor))
      macroStore <- Stream.eval(FileMacroStore[ET](config.stores.macroStore, executor))
      commandStore <- Stream.eval(
        FileRemoteCommandStore[ET, CommandPayload](config.stores.remoteCommandStore, executor)
      )

      switchStateFileStore <- Stream
        .resource[ET, SwitchStateStore[ET]](
          FileSwitchStateStore.polling[ET](
            config.stores.switchStateStore,
            config.stores.switchStatePolling,
            executor,
            notifyUpdate(buttonsUpdate, remotesUpdate, statsSwitchUpdate)
          )
        )

      rm2RemoteControls <- Stream.eval(RmRemoteControls[ET](config.rm.map(_.config), commandStore, executor))

      hs100SwitchProvider <- Stream
        .resource[ET, SwitchProvider[ET]](
          HS100SwitchProvider[ET](
            config.hs100.configs,
            config.hs100.polling,
            notifyUpdate(buttonsUpdate, remotesUpdate, statsSwitchUpdate),
            executor
          )
        )

      spSwitchProvider <- Stream.resource[ET, SwitchProvider[ET]](
        SpSwitchProvider[ET](
          config.sp.configs,
          config.sp.polling,
          switchStateFileStore,
          executor,
          notifyUpdate(buttonsUpdate, remotesUpdate, statsSwitchUpdate)
        )
      )

      sonosComponents <- Stream.resource[ET, SonosComponents[ET]](
        SonosComponents[ET](
          config.sonos,
          notifyUpdate(buttonsUpdate, remotesUpdate, roomsUpdate, statsConfigUpdate, statsSwitchUpdate),
          executor
        )
      )

      combinedActivityConfig = ActivityConfigSource.combined[ET](activityConfig, sonosComponents.activityConfig)
      combinedRemoteConfig = RemoteConfigSource.combined[ET](sonosComponents.remoteConfig, remoteConfig)
      remoteControls = RemoteControls
        .combined[ET](rm2RemoteControls, RemoteControls[ET](Map(config.sonos.remote -> sonosComponents.remote)))

      switchStateStore <- Stream.eval(
        SwitchDependentStore.fromProvider[ET](
          config.rm.flatMap(c => c.dependentSwitch.map(c.config.name -> _)).toMap,
          switchStateFileStore,
          SwitchProvider.combined(hs100SwitchProvider, spSwitchProvider)
        )
      )

      virtualSwitches <- Stream.resource[ET, SwitchProvider[ET]](
        SwitchesForRemote.polling[ET](
          config.virtualSwitch,
          virtualSwitchConfig,
          rm2RemoteControls,
          switchStateStore,
          notifyUpdate(buttonsUpdate, remotesUpdate, statsSwitchUpdate)
        )
      )

      combinedSwitchProvider = SwitchProvider
        .combined[ET](hs100SwitchProvider, spSwitchProvider, sonosComponents.switches, virtualSwitches)

      switches = Switches[ET](combinedSwitchProvider)
      macros = Macro[ET](macroStore, remoteControls, switches)
      activity <- Stream.eval(
        Activity.dependsOnSwitch[ET](
          config.activity.dependentSwitches,
          hs100SwitchProvider,
          activityStore,
          macros,
          notifyUpdate(activitiesUpdate)
        )
      )

      instrumentation <- Stream.resource[ET, StatsStream.Instrumented[ET]](
        StatsStream[ET](
          config.stats,
          activity,
          macros,
          remoteControls,
          switches,
          combinedActivityConfig,
          buttonConfig,
          macroStore,
          remoteConfig,
          combinedSwitchProvider
        )(statsConfigUpdate, statsSwitchUpdate)
      )

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
        instrumentation.activity,
        instrumentation.`macro`,
        instrumentation.remote,
        instrumentation.switch,
        combinedActivityConfig,
        configView,
        executor,
        UpdateTopics(activitiesUpdate, buttonsUpdate, remotesUpdate, roomsUpdate),
        registry,
        instrumentation.statsStream.translate(translateF)
      )
}
