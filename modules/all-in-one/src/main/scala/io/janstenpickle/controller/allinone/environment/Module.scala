package io.janstenpickle.controller.allinone.environment

import java.util.UUID

import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.syntax.concurrent._
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.mtl.ApplicativeHandle
import cats.mtl.implicits._
import cats.syntax.flatMap._
import cats.syntax.semigroup._
import cats.{~>, Applicative, ApplicativeError, MonadError, Parallel}
import extruder.core.ValidationErrorsToThrowable
import extruder.data.ValidationErrors
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.`macro`.store.{MacroStore, TracedMacroStore}
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.activity.store.{ActivityStore, TracedActivityStore}
import io.janstenpickle.controller.advertiser.{Advertiser, ServiceType}
import io.janstenpickle.controller.allinone.config.Configuration
import io.janstenpickle.controller.allinone.config.Configuration.Config
import io.janstenpickle.controller.allinone.error.ErrorInterpreter
import io.janstenpickle.controller.api.endpoint._
import io.janstenpickle.controller.api.service.ConfigService
import io.janstenpickle.controller.api.validation.ConfigValidation
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.components.events.EventDrivenComponents
import io.janstenpickle.controller.configsource._
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.deconz.{action, DeconzBridge}
import io.janstenpickle.controller.events.TopicEvents
import io.janstenpickle.controller.events.commands.EventCommands
import io.janstenpickle.controller.events.websocket.ServerWs
import io.janstenpickle.controller.http4s.client.EitherTClient
import io.janstenpickle.controller.http4s.error.{ControlError, Handler}
import io.janstenpickle.controller.http4s.trace.implicits._
import io.janstenpickle.controller.multiswitch.{MultiSwitchEventListenter, MultiSwitchProvider}
import io.janstenpickle.controller.remote.store.{RemoteCommandStore, TracedRemoteCommandStore}
import io.janstenpickle.controller.schedule.cron.CronScheduler
import io.janstenpickle.controller.server.Server
import io.janstenpickle.controller.stats.StatsTranslator
import io.janstenpickle.controller.stats.prometheus.MetricsSink
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switch.virtual.{SwitchDependentStore, SwitchesForRemote}
import io.janstenpickle.controller.switches.store.{SwitchStateStore, TracedSwitchStateStore}
import io.janstenpickle.controller.trace.instances._
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.inject.{EntryPoint, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue.LongValue
import io.janstenpickle.trace4cats.model.SpanKind
import io.prometheus.client.CollectorRegistry
import org.http4s.client.Client
import org.http4s.server.Router
import org.http4s.{HttpRoutes, Request, Response}

import scala.concurrent.duration._

object Module {
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
  ): Resource[F, (HttpRoutes[F], CollectorRegistry)] =
    for {
      blocker <- Blocker[F]
      registry <- Resources.registry[F]
      ep <- Resources.entryPoint[F](registry, blocker)
      client <- Resources.httpClient[F](registry, blocker)
      routes <- components(config, ep, client, registry)
    } yield (routes, registry)

  def components[F[_]: ContextShift: Timer: Parallel](
    config: Configuration.Config,
    ep: EntryPoint[F],
    client: Client[F],
//    mqtt: Option[Fs2MqttClient[F]],
    registry: CollectorRegistry
  )(implicit F: ConcurrentEffect[F]): Resource[F, HttpRoutes[F]] = {
    type G[A] = Kleisli[F, Span[F], A]

    val lift = λ[F ~> G](fa => Kleisli(_ => fa))

    implicit val liftLower: ContextualLiftLower[F, G, String] = ContextualLiftLower[F, G, String](lift, _ => lift)(
      λ[G ~> F](ga => Span.noop[F].use(ga.run)),
      name => λ[G ~> F](ga => ep.root(name).use(ga.run))
    )

    implicit val liftLowerContext: ContextualLiftLower[F, G, (String, Map[String, String])] =
      ContextualLiftLower[F, G, (String, Map[String, String])](lift, _ => lift)(
        λ[G ~> F](ga => Span.noop[F].use(ga.run)), {
          case (name, headers) => λ[G ~> F](ga => ep.continueOrElseRoot(name, SpanKind.Consumer, headers).use(ga.run))
        }
      )

    eitherTComponents[G, F](config, ep.lowerT(client), registry)
      .mapK(liftLower.lower)
      .map(ep.liftT)
  }

  private def eitherTComponents[F[_]: ContextShift: Timer: Parallel, M[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config,
    client: Client[F],
    registry: CollectorRegistry
  )(
    implicit F: Concurrent[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[M, F, String],
    liftLowerContext: ContextualLiftLower[M, F, (String, Map[String, String])]
  ): Resource[F, HttpRoutes[F]] = {
    type G[A] = EitherT[F, ControlError, A]

    val lift = λ[F ~> G](EitherT.liftF(_))

    val lower =
      λ[G ~> F](ga => ga.value.flatMap(_.fold(ApplicativeError[F, Throwable].raiseError, Applicative[F].pure)))

    implicit val ll: ContextualLiftLower[M, G, String] = liftLower.imapK[G](lift)(lower)
    implicit val llc: ContextualLiftLower[M, G, (String, Map[String, String])] = liftLowerContext.imapK[G](lift)(lower)

    Resource
      .liftF(Slf4jLogger.fromName[G]("Controller Error"))
      .flatMap { implicit logger =>
        tracedComponents[G, M](config, EitherTClient[F, ControlError](client), registry)
          .map { routes =>
            Kleisli[OptionT[F, *], Request[F], Response[F]] { req =>
              OptionT(Handler.handleControlError[G](routes.run(req.mapK(lift)).value)).mapK(lower).map(_.mapK(lower))
            }
          }
      }
      .mapK(lower)
  }

  private def tracedComponents[F[_]: ContextShift: Timer: Parallel, G[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config,
    client: Client[F],
    registry: CollectorRegistry
  )(
    implicit F: Concurrent[F],
    trace: Trace[F],
    errors: ErrorInterpreter[F],
    ah: ApplicativeHandle[F, ControlError],
    liftLower: ContextualLiftLower[G, F, String],
    liftLowerContext: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, HttpRoutes[F]] = {

    for {
      discoveryBlocker <- Server.blocker[F]("discovery")
      workBlocker <- Server.blocker[F]("work")

      events <- Resource.liftF(TopicEvents[F])

      eventComponents <- EventDrivenComponents(events, 5.seconds)

      _ <- MultiSwitchEventListenter(events.switch, events.config)

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
        "timeout" -> LongValue(config.config.writeTimeout.toMillis)
      )

      macroStore = TracedMacroStore(
        MacroStore.fromConfigSource(macroConfig),
        "config",
        "path" -> config.config.dir.resolve("macro").toString,
        "timeout" -> LongValue(config.config.writeTimeout.toMillis)
      )
//
//      githubRemoteConfigSource <- GithubRemoteCommandConfigSource[F, G](
//        client,
//        config.githubRemoteCommands,
//        (_, _) => F.unit
//      )

      commandStore = TracedRemoteCommandStore(
        RemoteCommandStore.fromConfigSource(
          remoteCommandConfig //WritableConfigSource.combined(remoteCommandConfig, githubRemoteConfigSource)
        ),
        "config",
        "path" -> config.config.dir.resolve("remote-command").toString,
        "timeout" -> LongValue(config.config.writeTimeout.toMillis)
      )

      switchStateFileStore = TracedSwitchStateStore(
        SwitchStateStore.fromConfigSource(switchStateConfig),
        "config",
        "path" -> config.config.dir.resolve("switch-state").toString,
        "timeout" -> LongValue(config.config.writeTimeout.toMillis)
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
        events.command.subscriberStream,
        context,
        mac,
        activity,
        allComponents.remotes,
        components.rename
      )

      actionProcessor <- Resource.liftF(action.CommandEventProcessor[F](events.command.publisher, deconzConfig))
      _ <- config.deconz.fold(Resource.pure[F, Unit](()))(DeconzBridge[F, G](_, actionProcessor, workBlocker))

      host <- Resource.liftF(Server.hostname[F](config.hostname))

      _ <- Advertiser[F](host, config.server.port, ServiceType.Coordinator)

      websocketCommandSource <- Resource.liftF(Sync[F].delay(UUID.randomUUID().toString))
      // do not forward events from the command websocket
      (eventsState, websockets) <- ServerWs[F, G](events, _.source != websocketCommandSource)
      _ <- Resource.liftF(eventsState.completeWithComponents(allComponents, "coordinator", events.source))
    } yield
      Router(
        "/events" -> websockets,
        "/control/remote" -> new RemoteApi[F](allComponents.remotes).routes,
        "/control/switch" -> new SwitchApi[F](switches).routes,
        "/control/macro" -> new MacroApi[F](mac).routes,
        "/control/activity" -> new ActivityApi[F](activity, allComponents.activityConfig).routes,
        "/control/context" -> new ContextApi[F](context).routes,
        "/command" -> new CommandWs[F, G](
          events.command.publisher.mapK(liftLower.lower, liftLower.lift),
          websocketCommandSource
        ).routes,
        "/config" -> new ConfigApi[F, G](configService, events.activity, events.config, events.switch).routes,
        "/config" -> new WritableConfigApi[F](configService).routes,
        "/discovery" -> new RenameApi[F](allComponents.rename).routes,
        "/schedule" -> new ScheduleApi[F](allComponents.scheduler).routes,
        "/" -> new ControllerUi[F](workBlocker, config.frontendPath).routes
      )
  }
}
