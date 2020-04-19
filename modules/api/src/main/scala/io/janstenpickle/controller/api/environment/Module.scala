package io.janstenpickle.controller.api.environment

import java.util.concurrent.{Executors, ThreadFactory}

import cats.data.{EitherT, Kleisli, OptionT, Reader}
import cats.effect.syntax.concurrent._
import cats.effect.{Blocker, Bracket, Concurrent, ConcurrentEffect, ContextShift, ExitCode, Resource, Sync, Timer}
import cats.mtl.ApplicativeHandle
import cats.mtl.implicits._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.{~>, Applicative, ApplicativeError, Id, MonadError, Parallel}
import extruder.cats.effect.EffectValidation
import extruder.core.ValidationErrorsToThrowable
import extruder.data.ValidationErrors
import fs2.Stream
import fs2.concurrent.Signal
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.activity.{Activity, ActivitySwitchProvider}
import io.janstenpickle.controller.api.config.Configuration
import io.janstenpickle.controller.api.endpoint._
import io.janstenpickle.controller.api.error.{ControlError, ErrorInterpreter, Handler}
import io.janstenpickle.controller.api.service.ConfigService
import io.janstenpickle.controller.api.trace.implicits._
import io.janstenpickle.controller.api.validation.ConfigValidation
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource._
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.events.commands.EventCommands
import io.janstenpickle.controller.events.mqtt.MqttEvents
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.homekit.ControllerHomekitServer
import io.janstenpickle.controller.mqtt.Fs2MqttClient
import io.janstenpickle.controller.multiswitch.{MultiSwitchEventListenter, MultiSwitchProvider}
import io.janstenpickle.controller.remotecontrol.git.GithubRemoteCommandConfigSource
import io.janstenpickle.controller.schedule.cron.CronScheduler
import io.janstenpickle.controller.stats.StatsTranslator
import io.janstenpickle.controller.stats.prometheus.MetricsSink
import io.janstenpickle.controller.store.trace.{
  TracedActivityStore,
  TracedMacroStore,
  TracedRemoteCommandStore,
  TracedSwitchStateStore
}
import io.janstenpickle.controller.store.{ActivityStore, MacroStore, RemoteCommandStore, SwitchStateStore}
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switch.virtual.{SwitchDependentStore, SwitchesForRemote}
import io.janstenpickle.controller.trace.EmptyTrace
import io.janstenpickle.deconz.DeconzBridge
import io.janstenpickle.deconz.action.CommandEventProcessor
import io.prometheus.client.CollectorRegistry
import natchez.TraceValue.NumberValue
import natchez._
import org.http4s.client.Client
import org.http4s.metrics.prometheus.PrometheusExportService
import org.http4s.server.Router
import org.http4s.{HttpRoutes, Request, Response}

import scala.concurrent.Future

object Module {
  type Homekit[F[_]] = Reader[(F ~> Future, F ~> Id, Signal[F, Boolean]), Stream[F, ExitCode]]

  def configOrError[F[_]](
    result: F[Either[ValidationErrors, Configuration.Config]]
  )(implicit F: MonadError[F, Throwable]): F[Configuration.Config] =
    result.flatMap(
      _.fold[F[Configuration.Config]](
        errs => F.raiseError(ValidationErrorsToThrowable.defaultValidationErrorsThrowable.convertErrors(errs)),
        F.pure
      )
    )

  def components[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]]
  ): Resource[F, (Configuration.Server, HttpRoutes[F], CollectorRegistry, Homekit[F])] =
    for {
      blocker <- Blocker[F]
      registry <- Resources.registry[F]
      ep <- Resources.entryPoint[F](registry, blocker)
      client <- Resources.httpClient[F](registry, blocker)
      config <- Resource.liftF(configOrError(getConfig()))
      mqtt <- Resources.mqttClient[F](config.mqtt)
      events <- config.kafka.fold(Resource.liftF(TopicEvents[F]))(KafkaEvents.create[F])
      cs <- components(getConfig, ep, client, events, mqtt, registry)
    } yield cs

  def components[F[_]: ContextShift: Timer: Parallel](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]],
    ep: EntryPoint[F],
    client: Client[F],
    events: Events[F],
    mqtt: Option[Fs2MqttClient[F]],
    registry: CollectorRegistry
  )(
    implicit F: ConcurrentEffect[F]
  ): Resource[F, (Configuration.Server, HttpRoutes[F], CollectorRegistry, Homekit[F])] = {
    type G[A] = Kleisli[F, Span[F], A]

    val lift = λ[F ~> G](fa => Kleisli(_ => fa))

    implicit val liftLower: ContextualLiftLower[F, G, String] = ContextualLiftLower[F, G, String](lift, _ => lift)(
      λ[G ~> F](_.run(EmptyTrace.emptySpan)),
      name => λ[G ~> F](ga => ep.root(name).use(ga.run))
    )

    val liftedConfig: () => G[Either[ValidationErrors, Configuration.Config]] = () => lift(getConfig())

    eitherTComponents[G, F](
      liftedConfig,
      ep.lowerT(client),
      events.mapK(lift, liftLower.lower),
      mqtt.map(_.mapK(lift, liftLower.lower)),
      registry
    ).mapK(liftLower.lower)
      .map {
        case (config, routes, registry, homekit) =>
          (config, ep.liftT(routes), registry, homekit)
      }
  }

  private def eitherTComponents[F[_]: ContextShift: Timer: Parallel, M[_]: ConcurrentEffect: ContextShift: Timer](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]],
    client: Client[F],
    events: Events[F],
    mqtt: Option[Fs2MqttClient[F]],
    registry: CollectorRegistry
  )(
    implicit F: Concurrent[F],
    trace: Trace[F],
    invk: ContextualLiftLower[M, F, String]
  ): Resource[F, (Configuration.Server, HttpRoutes[F], CollectorRegistry, Homekit[M])] = {
    type G[A] = EitherT[F, ControlError, A]

    val lift = λ[F ~> G](EitherT.liftF(_))

    val lower =
      λ[G ~> F](ga => ga.value.flatMap(_.fold(ApplicativeError[F, Throwable].raiseError, Applicative[F].pure)))

    implicit val cInvK: ContextualLiftLower[M, G, String] = invk.imapK[G](lift)(lower)

    implicit val etTrace: Trace[G] = new Trace[G] {
      override def put(fields: (String, TraceValue)*): EitherT[F, ControlError, Unit] =
        lift(trace.put(fields: _*))
      override def kernel: EitherT[F, ControlError, Kernel] = lift(trace.kernel)
      override def span[A](name: String)(k: EitherT[F, ControlError, A]): EitherT[F, ControlError, A] =
        EitherT(trace.span(name)(k.value))
    }

    implicit val bracket: Bracket[G, Throwable] = Sync[G]

    val liftedConfig: () => G[Either[ValidationErrors, Configuration.Config]] = () => lift(getConfig())

    Resource
      .liftF(Slf4jLogger.fromName[G]("Controller Error"))
      .flatMap { implicit logger =>
        tracedComponents[G, M](
          liftedConfig,
          eitherTClient(client),
          events.mapK(lift, lower),
          mqtt.map(_.mapK(lift, lower)),
          registry
        ).map {
          case (config, routes, registry, homekit) =>
            val r = Kleisli[OptionT[F, *], Request[F], Response[F]] { req =>
              OptionT(Handler.handleControlError[G](routes.run(req.mapK(lift)).value)).mapK(lower).map(_.mapK(lower))
            }

            (config, r, registry, homekit)
        }
      }
      .mapK(lower)
  }

  def eitherTClient[F[_]](client: Client[F])(implicit F: Sync[F]): Client[EitherT[F, ControlError, *]] = {
    type G[A] = EitherT[F, ControlError, A]
    val lift = λ[F ~> G](EitherT.liftF(_))
    val lower =
      λ[G ~> F](ga => ga.value.flatMap(_.fold(F.raiseError, Applicative[F].pure)))

    Client.fromHttpApp(Kleisli[G, Request[G], Response[G]] { req =>
      lift(client.toHttpApp.run(req.mapK(lower)).map(_.mapK(lift)))
    })
  }

  private def tracedComponents[F[_]: ContextShift: Timer: Parallel, G[_]: ConcurrentEffect: ContextShift: Timer](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]],
    client: Client[F],
    events: Events[F],
    mqtt: Option[Fs2MqttClient[F]],
    registry: CollectorRegistry
  )(
    implicit F: Concurrent[F],
    trace: Trace[F],
    errors: ErrorInterpreter[F],
    ah: ApplicativeHandle[F, ControlError],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, (Configuration.Server, HttpRoutes[F], CollectorRegistry, Homekit[G])] = {
    type ConfigResult[A] = EffectValidation[F, A]

    def makeBlocker(name: String) =
      Blocker.fromExecutorService(F.delay(Executors.newCachedThreadPool(new ThreadFactory {
        def newThread(r: Runnable) = {
          val t = new Thread(r, s"$name-blocker")
          t.setDaemon(true)
          t
        }
      })))

    for {
      config <- Resource.liftF[F, Configuration.Config](configOrError(getConfig()))
      discoveryBlocker <- makeBlocker("discovery")
      workBlocker <- makeBlocker("work")
      _ <- PrometheusExportService.addDefaults[F](registry)

      _ <- mqtt.fold(Resource.pure[F, Unit](()))(
        MqttEvents[F](config.mqtt.events, events.switch, events.config, events.remote, events.command, _)
      )

      _ <- MultiSwitchEventListenter(events.switch, events.config)

      homeKitSwitchEventSubscriber <- events.switch.subscriberResource

      _ <- StatsTranslator[F](
        events.config,
        events.activity,
        events.switch,
        events.remote,
        events.`macro`,
        MetricsSink[F](registry, workBlocker)
      )

      _ <- events.discovery.subscriberStream.subscribe.evalMap(e => F.delay(println(e))).compile.drain.background

      _ <- events.config.subscriberStream.subscribe.evalMap(e => F.delay(println(e))).compile.drain.background

      _ <- events.switch.subscriberStream.subscribe.evalMap(e => F.delay(println(e))).compile.drain.background

      _ <- events.command.subscriberStream.subscribe.evalMap(e => F.delay(println(e))).compile.drain.background

      (
        activityConfig,
        buttonConfig,
        remoteConfig,
        virtualSwitchConfig,
        multiSwitchConfig,
        currentActivityConfig,
        scheduleConfig,
        deconzConfig,
        discoveryMappingConfig,
        macroConfig,
        remoteCommandConfig,
        switchStateConfig
      ) <- ConfigSources.create[F, G](
        config.config,
        events.config.publisher,
        events.switch.publisher,
        events.activity.publisher,
        events.discovery.publisher,
        workBlocker
      )

      activityStore = TracedActivityStore(
        ActivityStore.fromConfigSource(currentActivityConfig),
        "config",
        "path" -> config.config.dir.resolve("current-activity").toString,
        "timeout" -> NumberValue(config.config.writeTimeout.toMillis)
      )

      macroStore = TracedMacroStore(
        MacroStore.fromConfigSource(macroConfig),
        "config",
        "path" -> config.config.dir.resolve("macro").toString,
        "timeout" -> NumberValue(config.config.writeTimeout.toMillis)
      )

      githubRemoteConfigSource <- GithubRemoteCommandConfigSource[F, G](
        client,
        config.githubRemoteCommands,
        (_, _) => F.unit
      )

      commandStore = TracedRemoteCommandStore(
        RemoteCommandStore.fromConfigSource(
          WritableConfigSource.combined(remoteCommandConfig, githubRemoteConfigSource)
        ),
        "config",
        "path" -> config.config.dir.resolve("remote-command").toString,
        "timeout" -> NumberValue(config.config.writeTimeout.toMillis)
      )

      switchStateFileStore = TracedSwitchStateStore(
        SwitchStateStore.fromConfigSource(switchStateConfig),
        "config",
        "path" -> config.config.dir.resolve("switch-state").toString,
        "timeout" -> NumberValue(config.config.writeTimeout.toMillis)
      )

      homekitConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("homekit"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      components <- ComponentsEnv.create[F, G](
        config,
        client,
        commandStore,
        switchStateFileStore,
        discoveryMappingConfig,
        workBlocker,
        discoveryBlocker,
        events.remote.publisher,
        events.switch.publisher,
        events.config.publisher,
        events.discovery.publisher
      )

      combinedActivityConfig = WritableConfigSource.combined(activityConfig, components.activityConfig)
      combinedRemoteConfig = WritableConfigSource.combined(remoteConfig, components.remoteConfig)

      switchStateStore = SwitchDependentStore[F](
        config.virtualSwitch.dependentSwitches,
        switchStateFileStore,
        components.switches
      )

      virtualSwitches <- SwitchesForRemote.polling[F, G](
        config.virtualSwitch.polling,
        virtualSwitchConfig,
        components.remotes,
        switchStateStore,
        events.switch.publisher.narrow
      )

      combinedSwitchProvider = components.switches |+| virtualSwitches

      multiSwitchProvider = MultiSwitchProvider[F](
        multiSwitchConfig,
        Switches[F](combinedSwitchProvider),
        events.switch.publisher.narrow
      )

      switches = Switches[F](combinedSwitchProvider |+| multiSwitchProvider)

      mac = Macro[F](macroStore, components.remotes, switches, events.`macro`.publisher)

      act = Activity.dependsOnSwitch[F](
        config.activity.dependentSwitches,
        combinedSwitchProvider,
        activityConfig,
        activityStore,
        mac,
        events.activity.publisher
      )

      (activity, activitySwitchProvider) = ActivitySwitchProvider[F](
        act,
        combinedActivityConfig,
        mac,
        events.switch.publisher.narrow
      )

      allSwitches = switches.addProvider(activitySwitchProvider)

      configService <- Resource.liftF(
        ConfigService(
          combinedActivityConfig,
          buttonConfig,
          combinedRemoteConfig,
          macroStore,
          activityStore,
          allSwitches,
          new ConfigValidation(combinedActivityConfig, components.remotes, macroStore, switches),
          events.config.publisher
        )
      )

      cronScheduler <- CronScheduler[F, G](mac, scheduleConfig)

      context = Context[F](activity, mac, components.remotes, switches, combinedActivityConfig)

      _ <- EventCommands(events.command.subscriberStream, context, mac)

      actionProcessor <- Resource.liftF(CommandEventProcessor[F](events.command.publisher, deconzConfig))
      _ <- config.deconz.fold(Resource.pure[F, Unit](()))(DeconzBridge[F, G](_, actionProcessor, workBlocker))
    } yield {
      val router =
        Router(
          "/control/remote" -> new RemoteApi[F](components.remotes).routes,
          "/control/switch" -> new SwitchApi[F](allSwitches).routes,
          "/control/macro" -> new MacroApi[F](mac).routes,
          "/control/activity" -> new ActivityApi[F](activity, combinedActivityConfig).routes,
          "/control/context" -> new ContextApi[F](context).routes,
          "/config" -> new ConfigApi[F, G](configService, events.activity, events.config, events.switch).routes,
          "/discovery" -> new RenameApi[F](components.rename).routes,
          "/schedule" -> new ScheduleApi[F](components.scheduler |+| cronScheduler).routes,
          "/" -> new ControllerUi[F](workBlocker).routes,
          "/" -> PrometheusExportService.service[F](registry)
        )

      val homekit = ControllerHomekitServer
        .stream[F, G](config.homekit, homekitConfigFileSource, homeKitSwitchEventSubscriber, events.command.publisher)
        .local[(G ~> Future, G ~> Id, Signal[G, Boolean])] {
          case (fkFuture, fk, signal) =>
            (
              fkFuture.compose(liftLower.lower("homekit")),
              fk.compose(liftLower.lower("homekit")),
              new Signal[F, Boolean] {
                override def discrete: Stream[F, Boolean] = signal.discrete.translate(liftLower.lift)
                override def continuous: Stream[F, Boolean] = signal.continuous.translate(liftLower.lift)
                override def get: F[Boolean] = liftLower.lift(signal.get)
              }
            )
        }
        .map(_.translate(liftLower.lower("homekit")))

      (config.server, router, registry, homekit)
    }
  }
}
