package io.janstenpickle.controller.coordinator.environment

import java.util.UUID
import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.syntax.concurrent._
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.mtl.ApplicativeHandle
import cats.mtl.implicits._
import cats.syntax.flatMap._
import cats.syntax.parallel._
import cats.syntax.semigroup._
import cats.{~>, Applicative, ApplicativeError, Monad, Parallel}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.`macro`.store.{MacroStore, TracedMacroStore}
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.activity.store.{ActivityStore, TracedActivityStore}
import io.janstenpickle.controller.advertiser.{Advertiser, ServiceType}
import io.janstenpickle.controller.api.endpoint._
import io.janstenpickle.controller.api.service.ConfigService
import io.janstenpickle.controller.api.validation.ConfigValidation
import io.janstenpickle.controller.components.events.EventDrivenComponents
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.coordinator.config.Configuration
import io.janstenpickle.controller.coordinator.error.ErrorInterpreter
import io.janstenpickle.controller.events.TopicEvents
import io.janstenpickle.controller.events.commands.EventCommands
import io.janstenpickle.controller.events.websocket.ServerWs
import io.janstenpickle.controller.http4s.error.{ControlError, Handler}
import io.janstenpickle.controller.multiswitch.{MultiSwitchEventListenter, MultiSwitchProvider}
import io.janstenpickle.controller.schedule.cron.CronScheduler
import io.janstenpickle.controller.server.Server
import io.janstenpickle.controller.stats.StatsTranslator
import io.janstenpickle.controller.stats.prometheus.MetricsSink
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switch.virtual.{SwitchDependentStore, SwitchesForRemote}
import io.janstenpickle.controller.switches.store.{SwitchStateStore, TracedSwitchStateStore}
import io.janstenpickle.controller.trace.prometheus.PrometheusSpanCompleter
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.avro.AvroSpanCompleter
import io.janstenpickle.trace4cats.base.context.{Local, Provide, Unlift}
import io.janstenpickle.trace4cats.inject.{EntryPoint, ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.AttributeValue.LongValue
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders, TraceProcess}
import io.prometheus.client.CollectorRegistry
import org.http4s.server.Router
import org.http4s.{HttpRoutes, Request, Response}

import scala.concurrent.duration._
import io.janstenpickle.trace4cats.http4s.server.syntax._

object Module {

  private final val serviceName = "controller"
  private final val process = TraceProcess(serviceName)

  def registry[F[_]: Sync]: Resource[F, CollectorRegistry] =
    Resource.make[F, CollectorRegistry](Sync[F].delay {
      new CollectorRegistry(true)
    })(r => Sync[F].delay(r.clear()))

  def entryPoint[F[_]: Concurrent: ContextShift: Timer: Parallel](
    registry: CollectorRegistry,
    blocker: Blocker
  ): Resource[F, EntryPoint[F]] =
    (AvroSpanCompleter.udp[F](blocker, process), PrometheusSpanCompleter[F](registry, blocker, process))
      .parMapN(_ |+| _)
      .map { completer =>
        EntryPoint[F](SpanSampler.always, completer)
      }

  def components[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    config: Configuration.Config
  ): Resource[F, (HttpRoutes[F], CollectorRegistry)] =
    for {
      blocker <- Blocker[F]
      reg <- registry[F]
      ep <- entryPoint[F](reg, blocker)
      routes <- components[F](config, reg, ep)
    } yield (routes, reg)

  def components[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    config: Configuration.Config,
    registry: CollectorRegistry,
    ep: EntryPoint[F]
  ): Resource[F, HttpRoutes[F]] = {
    type G[A] = Kleisli[F, Span[F], A]

    val lower = λ[G ~> F](ga => Span.noop[F].use(ga.run))

    eitherTComponents[G, F](config, registry, ep)
      .mapK(lower)
      .map(_.inject(ep))
  }

  private def eitherTComponents[F[_]: ContextShift: Timer: Parallel, M[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config,
    registry: CollectorRegistry,
    ep: EntryPoint[M]
  )(implicit F: Concurrent[F], trace: Trace[F], pv: Provide[M, F, Span[M]]): Resource[F, HttpRoutes[F]] = {
    type G[A] = EitherT[F, ControlError, A]

    val lift = λ[F ~> G](EitherT.liftF(_))

    val lower =
      λ[G ~> F](ga => ga.value.flatMap(_.fold(ApplicativeError[F, Throwable].raiseError, Applicative[F].pure)))

    implicit val provide: Provide[M, G, Span[M]] = pv.imapK(lift, lower)

    Resource
      .liftF(Slf4jLogger.fromName[G]("Controller Error"))
      .flatMap { implicit logger =>
        tracedComponents[G, M](config, registry, ep).map { routes =>
          val r = Kleisli[OptionT[F, *], Request[F], Response[F]] { req =>
            OptionT(Handler.handleControlError[G](routes.run(req.mapK(lift)).value)).mapK(lower).map(_.mapK(lower))
          }

          r
        }
      }
      .mapK(lower)
  }

  private def tracedComponents[F[_]: ContextShift: Timer: Parallel, G[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config,
    registry: CollectorRegistry,
    ep: EntryPoint[G]
  )(
    implicit F: Concurrent[F],
    trace: Trace[F],
    errors: ErrorInterpreter[F],
    ah: ApplicativeHandle[F, ControlError],
    provide: Provide[G, F, Span[G]],
  ): Resource[F, HttpRoutes[F]] = {
    val k: ResourceKleisli[G, SpanName, Span[G]] =
      ep.toKleisli.local(name => (name, SpanKind.Internal, TraceHeaders.empty))

    for {
      workBlocker <- Server.blocker[F]("work")
      events <- Resource.liftF(TopicEvents[F])

      _ <- events.discovery.subscriberStream.subscribeEvent.evalMap(e => F.delay(println(e))).compile.drain.background

      _ <- events.config.subscriberStream.subscribeEvent.evalMap(e => F.delay(println(e))).compile.drain.background

      _ <- events.switch.subscriberStream.subscribeEvent.evalMap(e => F.delay(println(e))).compile.drain.background

      _ <- events.command.subscriberStream.subscribeEvent.evalMap(e => F.delay(println(e))).compile.drain.background

      _ <- events.remote.subscriberStream.subscribeEvent.evalMap(e => F.delay(println(e))).compile.drain.background

      _ <- MultiSwitchEventListenter(events.switch, events.config)

      _ <- StatsTranslator[F](
        events.config,
        events.activity,
        events.switch,
        events.remote,
        events.`macro`,
        MetricsSink[F](registry, workBlocker)
      )

      components <- EventDrivenComponents(events, 10.seconds, 30.seconds, ep.toKleisli.local {
        case (name, headers) => (name, SpanKind.Consumer, TraceHeaders(headers))
      })

      (
        activityConfig,
        buttonConfig,
        remoteConfig,
        virtualSwitchConfig,
        multiSwitchConfig,
        currentActivityConfig,
        scheduleConfig,
        macroConfig,
        switchStateConfig
      ) <- ConfigSources.create[F, G](
        config.config,
        events.config.publisher,
        events.switch.publisher,
        events.activity.publisher,
        workBlocker,
        k
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

      switchStateFileStore = TracedSwitchStateStore(
        SwitchStateStore.fromConfigSource(switchStateConfig),
        "config",
        "path" -> config.config.dir.resolve("switch-state").toString,
        "timeout" -> LongValue(config.config.writeTimeout.toMillis)
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
        events.switch.publisher.narrow,
        k
      )

      combinedSwitchProvider = components.switches |+| virtualSwitches

      multiSwitchProvider = MultiSwitchProvider[F](
        multiSwitchConfig,
        Switches[F](combinedSwitchProvider),
        events.switch.publisher.narrow
      )

      cronScheduler <- CronScheduler[F, G](events.command.publisher, scheduleConfig, k)

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
        components.rename,
        ep.toKleisli.local { case (name, headers) => (name, SpanKind.Consumer, TraceHeaders(headers)) }
      )

      host <- Resource.liftF(Server.hostname[F](config.hostname))

      _ <- Advertiser[F](host, config.server.port, ServiceType.Coordinator)

      websocketCommandSource <- Resource.liftF(Sync[F].delay(UUID.randomUUID().toString))
      // do not forward events from the command websocket
      (eventsState, websockets) <- ServerWs[F, G](
        events,
        _.source != websocketCommandSource,
        ep.toKleisli.local(name => (name, SpanKind.Consumer, TraceHeaders.empty))
      )
      _ <- Resource.liftF(eventsState.completeWithComponents(allComponents, "coordinator", events.source))
    } yield
      Router(
        "/events" -> websockets,
        "/control/remote" -> new RemoteApi[F](allComponents.remotes).routes,
        "/control/switch" -> new SwitchApi[F](switches).routes,
        "/control/macro" -> new MacroApi[F](mac).routes,
        "/control/activity" -> new ActivityApi[F](activity, combinedActivityConfig).routes,
        "/control/context" -> new ContextApi[F](context).routes,
        "/command" -> new CommandWs[F, G](
          events.command.publisher.imapK[G](λ[F ~> G](fa => Span.noop[G].use(provide.provide(fa))), provide.liftK),
          websocketCommandSource
        ).routes,
        "/config" -> new ConfigApi[F, G](
          configService,
          events.activity,
          events.config,
          events.switch,
          ep.toKleisli.local(name => (name, SpanKind.Server, TraceHeaders.empty))
        ).routes,
        "/config" -> new WritableConfigApi[F](configService).routes,
        "/discovery" -> new RenameApi[F](allComponents.rename).routes,
        "/schedule" -> new ScheduleApi[F](allComponents.scheduler).routes,
        "/" -> new ControllerUi[F](workBlocker, config.frontendPath).routes
      )
  }
}
