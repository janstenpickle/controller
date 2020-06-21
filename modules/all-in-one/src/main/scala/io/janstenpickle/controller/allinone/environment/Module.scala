package io.janstenpickle.controller.allinone.environment

import java.net.InetAddress
import java.util.concurrent.{Executors, ThreadFactory}

import cats.data.{EitherT, Kleisli, OptionT, Reader}
import cats.effect.syntax.concurrent._
import cats.effect.{Blocker, Bracket, Concurrent, ConcurrentEffect, ContextShift, ExitCode, Resource, Sync, Timer}
import cats.instances.list._
import cats.mtl.ApplicativeHandle
import cats.mtl.implicits._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.syntax.traverse._
import cats.{~>, Applicative, ApplicativeError, Id, MonadError, Parallel}
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import extruder.cats.effect.EffectValidation
import extruder.core.ValidationErrorsToThrowable
import extruder.data.ValidationErrors
import fs2.Stream
import fs2.concurrent.Signal
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.`macro`.store.{MacroStore, TracedMacroStore}
import io.janstenpickle.controller.activity.store.{ActivityStore, TracedActivityStore}
import io.janstenpickle.controller.activity.{Activity, ActivitySwitchProvider}
import io.janstenpickle.controller.advertiser.{Advertiser, ServiceType}
import io.janstenpickle.controller.allinone.config.Configuration
import io.janstenpickle.controller.api.endpoint._
import io.janstenpickle.controller.allinone.error.ErrorInterpreter
import io.janstenpickle.controller.api.service.ConfigService
import io.janstenpickle.controller.http4s.trace.implicits._
import io.janstenpickle.controller.api.validation.ConfigValidation
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource._
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.event.switch.EventDrivenSwitchProvider
import io.janstenpickle.controller.events.commands.EventCommands
import io.janstenpickle.controller.events.mqtt.MqttEvents
import io.janstenpickle.controller.events.websocket.ServerWs
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.homekit.ControllerHomekitServer
import io.janstenpickle.controller.mqtt.Fs2MqttClient
import io.janstenpickle.controller.multiswitch.{MultiSwitchEventListenter, MultiSwitchProvider}
import io.janstenpickle.controller.remote.store.{RemoteCommandStore, TracedRemoteCommandStore}
import io.janstenpickle.controller.remotecontrol.git.GithubRemoteCommandConfigSource
import io.janstenpickle.controller.schedule.cron.CronScheduler
import io.janstenpickle.controller.stats.StatsTranslator
import io.janstenpickle.controller.stats.prometheus.MetricsSink
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switch.virtual.{SwitchDependentStore, SwitchesForRemote}
import io.janstenpickle.controller.switches.store.{SwitchStateStore, TracedSwitchStateStore}
import io.janstenpickle.controller.trace.EmptyTrace
import io.janstenpickle.deconz.DeconzBridge
import io.janstenpickle.deconz.action.CommandEventProcessor
import io.janstenpickle.controller.`macro`.store.TracedMacroStore
import io.janstenpickle.controller.allinone.config.Configuration.Config
import io.janstenpickle.controller.components.events.EventDrivenComponents
import io.janstenpickle.controller.event.config.EventDrivenConfigSource
import io.janstenpickle.controller.event.remotecontrol.EventDrivenRemoteControls
import io.janstenpickle.controller.events.kafka.events.KafkaEvents
import io.janstenpickle.controller.events.{Bridge, Events, TopicEvents}
import io.janstenpickle.controller.http4s.client.EitherTClient
import io.janstenpickle.controller.http4s.error.{ControlError, Handler}
import io.janstenpickle.controller.model.Remote
import io.janstenpickle.controller.model.event.{ConfigEvent, RemoteEvent}
import io.janstenpickle.controller.server.Server
import io.prometheus.client.CollectorRegistry
import natchez.TraceValue.NumberValue
import natchez._
import org.http4s.client.Client
import org.http4s.metrics.prometheus.PrometheusExportService
import org.http4s.server.Router
import org.http4s.{HttpRoutes, Request, Response}
import io.janstenpickle.controller.trace.instances._

import scala.concurrent.Future
import scala.concurrent.duration._

object Module {
  type Homekit[F[_]] = Reader[(F ~> Future, F ~> Id, Signal[F, Boolean]), Stream[F, ExitCode]]

  def configOrError[F[_]](
    result: F[Either[ValidationErrors, Config]]
  )(implicit F: MonadError[F, Throwable]): F[Configuration.Config] =
    result.flatMap(
      _.fold[F[Configuration.Config]](
        errs => F.raiseError(ValidationErrorsToThrowable.defaultValidationErrorsThrowable.convertErrors(errs)),
        F.pure
      )
    )

  def components[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    config: Configuration.Config
  ): Resource[F, (HttpRoutes[F], CollectorRegistry, Homekit[F])] =
    for {
      blocker <- Blocker[F]
      registry <- Resources.registry[F]
      ep <- Resources.entryPoint[F](registry, blocker)
      client <- Resources.httpClient[F](registry, blocker)
      mqtt <- Resources.mqttClient[F](config.mqtt)
      host <- Resource.liftF(Server.hostname(config.hostname))
//      _ <- config.kafka.fold(Resource.pure[F, Unit](()))(
//        KafkaEvents
//          .create[F](host, _)
//          .flatMap { kafkaEvents =>
//            Bridge(kafkaEvents, topicEvents)
//          }
//          .void
//      )
      (routes, homekit) <- components(config, ep, client, mqtt, registry)
    } yield (routes, registry, homekit)

  def components[F[_]: ContextShift: Timer: Parallel](
    config: Configuration.Config,
    ep: EntryPoint[F],
    client: Client[F],
    mqtt: Option[Fs2MqttClient[F]],
    registry: CollectorRegistry
  )(implicit F: ConcurrentEffect[F]): Resource[F, (HttpRoutes[F], Homekit[F])] = {
    type G[A] = Kleisli[F, Span[F], A]

    val lift = λ[F ~> G](fa => Kleisli(_ => fa))

    implicit val liftLower: ContextualLiftLower[F, G, String] = ContextualLiftLower[F, G, String](lift, _ => lift)(
      λ[G ~> F](_.run(EmptyTrace.emptySpan)),
      name => λ[G ~> F](ga => ep.root(name).use(ga.run))
    )

    implicit val liftLowerContext: ContextualLiftLower[F, G, (String, Map[String, String])] =
      ContextualLiftLower[F, G, (String, Map[String, String])](lift, _ => lift)(
        λ[G ~> F](_.run(EmptyTrace.emptySpan)), {
          case (name, headers) => λ[G ~> F](ga => ep.continueOrElseRoot(name, Kernel(headers)).use(ga.run))
        }
      )

    eitherTComponents[G, F](config, ep.lowerT(client), mqtt.map(_.mapK(lift, liftLower.lower)), registry)
      .mapK(liftLower.lower)
      .map {
        case (routes, homekit) =>
          (ep.liftT(routes), homekit)
      }
  }

  private def eitherTComponents[F[_]: ContextShift: Timer: Parallel, M[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config,
    client: Client[F],
    mqtt: Option[Fs2MqttClient[F]],
    registry: CollectorRegistry
  )(
    implicit F: Concurrent[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[M, F, String],
    liftLowerContext: ContextualLiftLower[M, F, (String, Map[String, String])]
  ): Resource[F, (HttpRoutes[F], Homekit[M])] = {
    type G[A] = EitherT[F, ControlError, A]

    val lift = λ[F ~> G](EitherT.liftF(_))

    val lower =
      λ[G ~> F](ga => ga.value.flatMap(_.fold(ApplicativeError[F, Throwable].raiseError, Applicative[F].pure)))

    implicit val ll: ContextualLiftLower[M, G, String] = liftLower.imapK[G](lift)(lower)
    implicit val llc: ContextualLiftLower[M, G, (String, Map[String, String])] = liftLowerContext.imapK[G](lift)(lower)

    Resource
      .liftF(Slf4jLogger.fromName[G]("Controller Error"))
      .flatMap { implicit logger =>
        tracedComponents[G, M](config, EitherTClient[F, ControlError](client), mqtt.map(_.mapK(lift, lower)), registry)
          .map {
            case (routes, homekit) =>
              val r = Kleisli[OptionT[F, *], Request[F], Response[F]] { req =>
                OptionT(Handler.handleControlError[G](routes.run(req.mapK(lift)).value)).mapK(lower).map(_.mapK(lower))
              }

              (r, homekit)
          }
      }
      .mapK(lower)
  }

  private def tracedComponents[F[_]: ContextShift: Timer: Parallel, G[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config,
    client: Client[F],
    mqtt: Option[Fs2MqttClient[F]],
    registry: CollectorRegistry
  )(
    implicit F: Concurrent[F],
    trace: Trace[F],
    errors: ErrorInterpreter[F],
    ah: ApplicativeHandle[F, ControlError],
    liftLower: ContextualLiftLower[G, F, String],
    liftLowerContext: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, (HttpRoutes[F], Homekit[G])] = {

    for {
      discoveryBlocker <- Server.blocker[F]("discovery")
      workBlocker <- Server.blocker[F]("work")

      events <- Resource.liftF(TopicEvents[F])

      _ <- mqtt.fold(Resource.pure[F, Unit](()))(
        MqttEvents[F](config.mqtt.events, events.switch, events.config, events.remote, events.command, _)
      )

      eventComponents <- EventDrivenComponents(events, 5.seconds)

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

      _ <- events.remote.subscriberStream.subscribe.evalMap(e => F.delay(println(e))).compile.drain.background

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

      components <- ComponentsEnv
        .create[F, G](
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
        .map(_ |+| eventComponents)

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

//
//      (activity, activitySwitchProvider) = ActivitySwitchProvider[F](
//        act,
//        combinedActivityConfig,
//        mac,
//        events.switch.publisher.narrow
//      )

//      allSwitches = switches.addProvider(activitySwitchProvider)

      cronScheduler <- CronScheduler[F, G](events.command.publisher, scheduleConfig)

      allComponents = components
        .copy(
          macroConfig = components.macroConfig |+| macroConfig,
          activityConfig = combinedActivityConfig,
          buttonConfig = buttonConfig,
          remoteConfig = combinedRemoteConfig,
          scheduler = components.scheduler |+| cronScheduler,
          switches = combinedSwitchProvider |+| multiSwitchProvider
        )

      switches = Switches[F](allComponents.switches)

      mac = Macro[F](macroStore, allComponents.remotes, switches, events.`macro`.publisher)

      activity = Activity.dependsOnSwitch[F](
        config.activity.dependentSwitches,
        allComponents.switches,
        activityConfig,
        activityStore,
        mac,
        events.activity.publisher
      )

      configService <- Resource.liftF(
        ConfigService(
          combinedActivityConfig,
          buttonConfig,
          combinedRemoteConfig,
          macroStore,
          activityStore,
          switches,
          new ConfigValidation(combinedActivityConfig, allComponents.remotes, macroStore, switches),
          events.config.publisher
        )
      )

      context = Context[F](activity, mac, allComponents.remotes, switches, combinedActivityConfig)

      _ <- EventCommands[F, G](
        events.command.subscriberStream.filterEvent(_.source != events.source),
        context,
        mac,
        activity,
        allComponents.remotes,
        components.rename
      )

      actionProcessor <- Resource.liftF(CommandEventProcessor[F](events.command.publisher, deconzConfig))
      _ <- config.deconz.fold(Resource.pure[F, Unit](()))(DeconzBridge[F, G](_, actionProcessor, workBlocker))

      host <- Resource.liftF(Server.hostname[F](config.hostname))

      _ <- Advertiser[F](host, config.server.port, ServiceType.Coordinator)
    } yield {

      val router =
        Router(
          "/events" -> ServerWs.receive[F, G](events),
          "/control/remote" -> new RemoteApi[F](allComponents.remotes).routes,
          "/control/switch" -> new SwitchApi[F](switches).routes,
          "/control/macro" -> new MacroApi[F](mac).routes,
          "/control/activity" -> new ActivityApi[F](activity, allComponents.activityConfig).routes,
          "/control/context" -> new ContextApi[F](context).routes,
          "/command" -> new CommandWs[F, G](events.command.publisher.mapK(liftLower.lower, liftLower.lift)).routes,
          "/config" -> new ConfigApi[F, G](configService, events.activity, events.config, events.switch).routes,
          "/config" -> new WritableConfigApi[F](configService).routes,
          "/discovery" -> new RenameApi[F](allComponents.rename).routes,
          "/schedule" -> new ScheduleApi[F](allComponents.scheduler).routes,
          "/" -> new ControllerUi[F](workBlocker).routes
        )

      val homekit = ControllerHomekitServer
        .stream[F, G](
          host,
          config.homekit,
          homekitConfigFileSource,
          homeKitSwitchEventSubscriber,
          events.command.publisher
        )
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

      (router, homekit)
    }
  }
}