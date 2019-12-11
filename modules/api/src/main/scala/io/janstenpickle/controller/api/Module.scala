package io.janstenpickle.controller.api

import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.{Blocker, Bracket, Clock, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.instances.list._
import cats.instances.parallel._
import cats.mtl.ApplicativeHandle
import cats.mtl.implicits._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.semigroup._
import cats.{~>, Applicative, ApplicativeError, Parallel}
import extruder.cats.effect.EffectValidation
import extruder.core.ValidationErrorsToThrowable
import extruder.data.ValidationErrors
import fs2.Stream
import fs2.concurrent.Topic
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.jaegertracing.Configuration.{ReporterConfiguration, SamplerConfiguration}
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.api.config.Configuration
import io.janstenpickle.controller.api.endpoint._
import io.janstenpickle.controller.api.error.{ControlError, ErrorInterpreter}
import io.janstenpickle.controller.api.service.ConfigService
import io.janstenpickle.controller.api.trace.implicits._
import io.janstenpickle.controller.api.validation.ConfigValidation
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.BroadlinkComponents
import io.janstenpickle.controller.cache.monitoring.CacheCollector
import io.janstenpickle.controller.configsource._
import io.janstenpickle.controller.configsource.extruder._
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.kodi.KodiComponents
import io.janstenpickle.controller.multiswitch.MultiSwitchProvider
import io.janstenpickle.controller.remotecontrol.git.GithubRemoteCommandConfigSource
import io.janstenpickle.controller.sonos.SonosComponents
import io.janstenpickle.controller.stats.StatsStream
import io.janstenpickle.controller.stats.prometheus.MetricsSink
import io.janstenpickle.controller.store.trace.{
  TracedActivityStore,
  TracedMacroStore,
  TracedRemoteCommandStore,
  TracedSwitchStateStore
}
import io.janstenpickle.controller.store.{ActivityStore, MacroStore, RemoteCommandStore, SwitchStateStore}
import io.janstenpickle.controller.switch.virtual.{SwitchDependentStore, SwitchesForRemote}
import io.janstenpickle.controller.switch.{SwitchProvider, Switches}
import io.janstenpickle.controller.tplink.TplinkComponents
import io.janstenpickle.controller.trace.EmptyTrace
import io.janstenpickle.controller.trace.prometheus.PrometheusTracer
import io.janstenpickle.controller.trace.instances._
import io.prometheus.client.CollectorRegistry
import natchez.TraceValue.NumberValue
import natchez._
import natchez.jaeger.Jaeger
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.{GZip, Metrics}
import org.http4s.dsl.Http4sDsl
import org.http4s.metrics.prometheus.{Prometheus, PrometheusExportService}
import org.http4s.server.Router
import org.http4s.{HttpRoutes, Request, Response}
import org.scalactic.anyvals.NonEmptyString

object Module {
  private final val serviceName = "controller"

  def makeRegistry[F[_]: Sync]: Resource[F, CollectorRegistry] =
    Resource.make[F, CollectorRegistry](Sync[F].delay {
      val registry = new CollectorRegistry(true)
      registry.register(new CacheCollector())
      registry
    })(r => Sync[F].delay(r.clear()))

  def entryPoint[F[_]: Sync: ContextShift: Clock](
    registry: CollectorRegistry,
    blocker: Blocker
  ): Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint[F](serviceName) { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv)
          .withReporter(ReporterConfiguration.fromEnv)
          .getTracer
      }
    } |+| PrometheusTracer.entryPoint[F](serviceName, registry, blocker)

  def httpClient[F[_]: ConcurrentEffect: ContextShift: Clock](
    registry: CollectorRegistry,
    blocker: Blocker
  ): Resource[F, Client[F]] =
    for {
      metrics <- Prometheus.metricsOps(registry, "org_http4s_client")
      builder = BlazeClientBuilder(blocker.blockingContext)
      client <- builder.resource
    } yield GZip()(Metrics(metrics)(client))

  def components[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]]
  ): Resource[F, (Configuration.Server, HttpRoutes[F], CollectorRegistry, Stream[F, Unit])] =
    for {
      blocker <- Blocker[F]
      registry <- makeRegistry[F]
      ep <- entryPoint[F](registry, blocker)
      client <- httpClient[F](registry, blocker)
      cs <- components(getConfig, ep, client, registry)
    } yield cs

  def components[F[_]: ContextShift: Timer: Parallel](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]],
    ep: EntryPoint[F],
    client: Client[F],
    registry: CollectorRegistry
  )(
    implicit F: Concurrent[F]
  ): Resource[F, (Configuration.Server, HttpRoutes[F], CollectorRegistry, Stream[F, Unit])] = {
    type G[A] = Kleisli[F, Span[F], A]

    val lift = λ[F ~> G](fa => Kleisli(_ => fa))

    implicit val liftLower: ContextualLiftLower[F, G, String] = ContextualLiftLower[F, G, String](lift, _ => lift)(
      λ[G ~> F](_.run(EmptyTrace.emptySpan)),
      name => λ[G ~> F](ga => ep.root(name).use(ga.run))
    )

    val liftedConfig: () => G[Either[ValidationErrors, Configuration.Config]] = () => lift(getConfig())

    eitherTComponents[G, F](liftedConfig, ep.lowerT(client), registry).mapK(liftLower.lower).map {
      case (config, routes, registry, stats) =>
        (config, ep.liftT(routes), registry, stats)
    }
  }

  private def eitherTComponents[F[_]: ContextShift: Timer: Parallel, M[_]: Concurrent: ContextShift: Timer](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]],
    client: Client[F],
    registry: CollectorRegistry
  )(
    implicit F: Concurrent[F],
    trace: Trace[F],
    invk: ContextualLiftLower[M, F, String]
  ): Resource[F, (Configuration.Server, HttpRoutes[F], CollectorRegistry, Stream[M, Unit])] = {
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
        tracedComponents[G, M](liftedConfig, eitherTClient(client), registry).map {
          case (config, routes, registry, stream) =>
            val r = Kleisli[OptionT[F, *], Request[F], Response[F]] { req =>
              OptionT(handleControlError[G](routes.run(req.mapK(lift)).value)).mapK(lower).map(_.mapK(lower))
            }

            (config, r, registry, stream)
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

  def handleControlError[F[_]: Sync](
    result: F[Option[Response[F]]]
  )(implicit trace: Trace[F], logger: Logger[F], ah: ApplicativeHandle[F, ControlError]): F[Option[Response[F]]] = {
    val dsl = Http4sDsl[F]
    import dsl._

    ah.handleWith(result) {
      case err @ ControlError.Missing(message) =>
        trace.put("error" -> true, "reason" -> "missing", "message" -> message) *> logger
          .warn(err)("Missing resource") *> NotFound(message).map(Some(_))
      case err @ ControlError.Internal(message) =>
        trace.put("error" -> true, "reason" -> "internal error", "message" -> message) *> logger
          .error(err)("Internal error") *> InternalServerError(message)
          .map(Some(_))
      case err @ ControlError.InvalidInput(message) =>
        trace.put("error" -> true, "reason" -> "invalid input", "message" -> message) *> logger
          .info(err)("Invalid input") *> BadRequest(message).map(Some(_))
      case err @ ControlError.Combined(_, _) if err.isSevere =>
        trace.put("error" -> true, "reason" -> "internal error", "message" -> err.message) *> logger
          .error(err)("Internal error") *> InternalServerError(err.message)
          .map(Some(_))
      case err @ ControlError.Combined(_, _) =>
        trace.put("error" -> true, "reason" -> "missing", "message" -> err.message) *> logger
          .warn(err)("Missing resource") *> NotFound(err.message).map(Some(_))
    }
  }

  private def tracedComponents[F[_]: ContextShift: Timer: Parallel, G[_]: Concurrent: ContextShift: Timer](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]],
    client: Client[F],
    registry: CollectorRegistry
  )(
    implicit F: Concurrent[F],
    trace: Trace[F],
    errors: ErrorInterpreter[F],
    ah: ApplicativeHandle[F, ControlError],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, (Configuration.Server, HttpRoutes[F], CollectorRegistry, Stream[G, Unit])] = {
    type ConfigResult[A] = EffectValidation[F, A]

    def configOrError(result: F[Either[ValidationErrors, Configuration.Config]]): F[Configuration.Config] =
      result.flatMap(
        _.fold[F[Configuration.Config]](
          errs => F.raiseError(ValidationErrorsToThrowable.defaultValidationErrorsThrowable.convertErrors(errs)),
          F.pure
        )
      )

    def notifyUpdate[A](topic: Topic[F, Boolean], topics: Topic[F, Boolean]*): A => F[Unit] =
      _ => (topic :: topics.toList).parTraverse(_.publish1(true)).void

    for {
      config <- Resource.liftF[F, Configuration.Config](configOrError(getConfig()))
      discoveryBlocker <- Blocker[F]
      workBlocker <- Blocker[F]
      _ <- PrometheusExportService.addDefaults[F](registry)
      activitiesUpdate <- Resource.liftF(Topic[F, Boolean](false))
      buttonsUpdate <- Resource.liftF(Topic[F, Boolean](false))
      remotesUpdate <- Resource.liftF(Topic[F, Boolean](false))
      roomsUpdate <- Resource.liftF(Topic[F, Boolean](false))
      statsSwitchUpdate <- Resource.liftF(Topic[F, Boolean](false))
      statsConfigUpdate <- Resource.liftF(Topic[F, Boolean](false))

      activityConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("activity"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      activityConfig <- ExtruderActivityConfigSource[F, G](
        activityConfigFileSource,
        config.config.polling,
        notifyUpdate(activitiesUpdate, roomsUpdate, statsConfigUpdate)
      )

      buttonConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("button"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      buttonConfig <- ExtruderButtonConfigSource[F, G](
        buttonConfigFileSource,
        config.config.polling,
        notifyUpdate(buttonsUpdate, roomsUpdate, statsConfigUpdate)
      )

      remoteConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("remote"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      remoteConfig <- ExtruderRemoteConfigSource[F, G](
        remoteConfigFileSource,
        config.config.polling,
        notifyUpdate(remotesUpdate, roomsUpdate, statsConfigUpdate)
      )

      virtualSwitchConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("virtual-switch"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      virtualSwitchConfig <- ExtruderVirtualSwitchConfigSource[F, G](
        virtualSwitchConfigFileSource,
        config.config.polling,
        notifyUpdate(remotesUpdate, roomsUpdate, statsSwitchUpdate)
      )

      multiSwitchConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("multi-switch"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      multiSwitchConfig <- ExtruderMultiSwitchConfigSource[F, G](
        multiSwitchConfigFileSource,
        config.config.polling,
        notifyUpdate(remotesUpdate, roomsUpdate, statsSwitchUpdate)
      )

      currentActivityConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("current-activity"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      activityStore <- ExtruderCurrentActivityConfigSource[F, G](
        currentActivityConfigFileSource,
        config.config.polling,
        notifyUpdate(remotesUpdate, roomsUpdate, statsSwitchUpdate)
      ).map { cs =>
        TracedActivityStore(
          ActivityStore.fromConfigSource(cs),
          "config",
          "path" -> config.config.dir.resolve("current-activity").toString,
          "timeout" -> NumberValue(config.config.writeTimeout.toMillis)
        )
      }

      discoveryMappingConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("discovery-mapping"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      discoveryMappingStore <- ExtruderDiscoveryMappingConfigSource[F, G](
        discoveryMappingConfigFileSource,
        config.config.polling,
        notifyUpdate(remotesUpdate, roomsUpdate, statsSwitchUpdate)
      )

      macroConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("macro"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      macroStore <- ExtruderMacroConfigSource[F, G](
        macroConfigFileSource,
        config.config.polling,
        notifyUpdate(remotesUpdate, roomsUpdate, statsSwitchUpdate)
      ).map { cs =>
        TracedMacroStore(
          MacroStore.fromConfigSource(cs),
          "config",
          "path" -> config.config.dir.resolve("macro").toString,
          "timeout" -> NumberValue(config.config.writeTimeout.toMillis)
        )
      }

      remoteCommandConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("remote-command"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      remoteConfigSource <- ExtruderRemoteCommandConfigSource[F, G](
        remoteCommandConfigFileSource,
        config.config.polling,
        notifyUpdate(remotesUpdate, roomsUpdate, statsSwitchUpdate)
      )

      githubRemoteConfigSource <- GithubRemoteCommandConfigSource[F, G](
        client,
        config.githubRemoteCommands,
        notifyUpdate(remotesUpdate, roomsUpdate, statsSwitchUpdate)
      )

      commandStore = TracedRemoteCommandStore(
        RemoteCommandStore.fromConfigSource(
          WritableConfigSource.combined(remoteConfigSource, githubRemoteConfigSource)
        ),
        "config",
        "path" -> config.config.dir.resolve("remote-command").toString,
        "timeout" -> NumberValue(config.config.writeTimeout.toMillis)
      )

      switchStateConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("switch-state"),
          config.config.polling.pollInterval,
          workBlocker,
          config.config.writeTimeout
        )

      switchStateFileStore <- ExtruderSwitchStateConfigSource[F, G](
        switchStateConfigFileSource,
        config.config.polling,
        notifyUpdate(buttonsUpdate, remotesUpdate, statsSwitchUpdate)
      ).map { cs =>
        TracedSwitchStateStore(
          SwitchStateStore.fromConfigSource(cs),
          "config",
          "path" -> config.config.dir.resolve("switch-state").toString,
          "timeout" -> NumberValue(config.config.writeTimeout.toMillis)
        )
      }

      broadlinkComponents <- BroadlinkComponents[F, G](
        config.broadlink,
        commandStore,
        switchStateFileStore,
        discoveryMappingStore,
        workBlocker,
        discoveryBlocker,
        () => notifyUpdate(buttonsUpdate, remotesUpdate, roomsUpdate, statsConfigUpdate, statsSwitchUpdate)(()),
        () => notifyUpdate(remotesUpdate, statsSwitchUpdate, statsConfigUpdate)(())
      )

      tplinkComponents <- TplinkComponents[F, G](
        config.tplink,
        workBlocker,
        discoveryBlocker,
        () => notifyUpdate(buttonsUpdate, remotesUpdate, roomsUpdate, statsConfigUpdate, statsSwitchUpdate)(()),
        () => notifyUpdate(remotesUpdate, statsSwitchUpdate, statsConfigUpdate)(())
      )

      sonosComponents <- SonosComponents[F, G](
        config.sonos,
        () => notifyUpdate(buttonsUpdate, remotesUpdate, roomsUpdate, statsConfigUpdate, statsSwitchUpdate)(()),
        workBlocker,
        discoveryBlocker,
        () => notifyUpdate(remotesUpdate, statsSwitchUpdate, statsConfigUpdate)(())
      )

      kodiComponents <- KodiComponents[F, G](
        client,
        discoveryBlocker,
        config.kodi,
        discoveryMappingStore,
        () => notifyUpdate(buttonsUpdate, remotesUpdate, roomsUpdate, statsConfigUpdate, statsSwitchUpdate)(()),
        () => notifyUpdate(remotesUpdate, statsSwitchUpdate, statsConfigUpdate)(())
      )

      components = broadlinkComponents |+| tplinkComponents |+| sonosComponents |+| kodiComponents

      combinedActivityConfig = WritableConfigSource.combined(activityConfig, components.activityConfig)
      combinedRemoteConfig = WritableConfigSource.combined(remoteConfig, components.remoteConfig)

      switchStateStore = SwitchDependentStore[F](
        config.virtualSwitch.dependentSwitches,
        switchStateFileStore,
        components.switches
      )

      virtualSwitches <- SwitchesForRemote.polling[F](
        config.virtualSwitch.polling,
        virtualSwitchConfig,
        components.remotes,
        switchStateStore,
        notifyUpdate(buttonsUpdate, remotesUpdate, statsSwitchUpdate)
      )

      combinedSwitchProvider = components.switches |+| virtualSwitches

      multiSwitchProvider = MultiSwitchProvider[F](multiSwitchConfig, Switches[F](combinedSwitchProvider))

      switches = Switches[F](combinedSwitchProvider |+| multiSwitchProvider)

      instrumentation <- StatsStream[F](
        config.stats,
        components.remotes,
        switches,
        combinedActivityConfig,
        buttonConfig,
        macroStore,
        combinedRemoteConfig,
        combinedSwitchProvider
      )(
        m =>
          Activity.dependsOnSwitch[F](
            config.activity.dependentSwitches,
            combinedSwitchProvider,
            activityConfig,
            activityStore,
            m,
            notifyUpdate(activitiesUpdate)
        ),
        (r, s) => Macro[F](macroStore, r, s)
      )(statsConfigUpdate, statsSwitchUpdate)

      l <- Resource.liftF(Slf4jLogger.fromName[F]("stats"))
      configService <- Resource.liftF(
        ConfigService(
          combinedActivityConfig,
          buttonConfig,
          combinedRemoteConfig,
          macroStore,
          activityStore,
          switches,
          new ConfigValidation(combinedActivityConfig, instrumentation.remote, macroStore, instrumentation.switch)
        )
      )
    } yield {
      val router =
        Router(
          "/control/remote" -> new RemoteApi[F](instrumentation.remote).routes,
          "/control/switch" -> new SwitchApi[F](instrumentation.switch).routes,
          "/control/macro" -> new MacroApi[F](instrumentation.`macro`).routes,
          "/control/activity" -> new ActivityApi[F](instrumentation.activity, combinedActivityConfig).routes,
          "/control/context" -> new ContextApi[F](
            instrumentation.activity,
            instrumentation.`macro`,
            instrumentation.remote,
            instrumentation.switch,
            combinedActivityConfig
          ).routes,
          "/config" -> new ConfigApi[F, G](
            configService,
            UpdateTopics(activitiesUpdate, buttonsUpdate, remotesUpdate, roomsUpdate)
          ).routes,
          "/discovery" -> new RenameApi[F](components.rename).routes,
          "/" -> new ControllerUi[F](workBlocker).routes,
          "/" -> PrometheusExportService.service[F](registry)
        )

      val logger: Logger[G] = l.mapK(liftLower.lower)

      val stats: Stream[G, Unit] = {
        val fullStream =
          instrumentation.statsStream.translate(liftLower.lower).through(MetricsSink[G](registry, workBlocker))

        fullStream.handleErrorWith { th =>
          Stream.eval(logger.error(th)("Stats publish failed")).flatMap(_ => fullStream)
        }
      }
      (config.server, router, registry, stats)
    }
  }
}
