package io.janstenpickle.controller.api

import java.util.concurrent.Executors

import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.{Blocker, Bracket, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.instances.list._
import cats.instances.parallel._
import cats.mtl.ApplicativeHandle
import cats.mtl.implicits._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
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
import io.janstenpickle.controller.api.trace.implicits._
import io.janstenpickle.controller.api.service.ConfigService
import io.janstenpickle.controller.api.validation.ConfigValidation
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.remote.RmRemoteControls
import io.janstenpickle.controller.broadlink.switch.SpSwitchProvider
import io.janstenpickle.controller.cache.monitoring.CacheCollector
import io.janstenpickle.controller.configsource._
import io.janstenpickle.controller.configsource.extruder._
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.CommandPayload
import io.janstenpickle.controller.multiswitch.MultiSwitchProvider
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.sonos.SonosComponents
import io.janstenpickle.controller.stats.StatsStream
import io.janstenpickle.controller.stats.prometheus.MetricsSink
import io.janstenpickle.controller.store.file._
import io.janstenpickle.controller.switch.hs100.HS100SwitchProvider
import io.janstenpickle.controller.switch.virtual.{SwitchDependentStore, SwitchesForRemote}
import io.janstenpickle.controller.switch.{SwitchProvider, Switches}
import io.prometheus.client.CollectorRegistry
import natchez._
import natchez.jaeger.Jaeger
import org.http4s.dsl.Http4sDsl
import org.http4s.metrics.prometheus.PrometheusExportService
import org.http4s.server.Router
import org.http4s.{HttpRoutes, Request, Response}

object Module {
  def entryPoint[F[_]: Sync]: Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint[F]("controller") { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv)
          .withReporter(ReporterConfiguration.fromEnv)
          .getTracer
      }
    }

  def components[F[_]: Concurrent: ContextShift: Timer: Parallel](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]]
  ): Resource[F, (Configuration.Server, HttpRoutes[F], CollectorRegistry, Stream[F, Unit])] =
    entryPoint[F].flatMap(components(getConfig, _))

  def emptySpan[F[_]](implicit F: Applicative[F]): Span[F] = new Span[F] {
    override def put(fields: (String, TraceValue)*): F[Unit] = F.unit
    override def kernel: F[Kernel] = F.pure(Kernel(Map.empty))
    override def span(name: String): Resource[F, Span[F]] = Resource.pure(emptySpan)
  }

  def components[F[_]: ContextShift: Timer: Parallel](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]],
    ep: EntryPoint[F]
  )(
    implicit F: Concurrent[F]
  ): Resource[F, (Configuration.Server, HttpRoutes[F], CollectorRegistry, Stream[F, Unit])] = {
    type G[A] = Kleisli[F, Span[F], A]

    val lift = λ[F ~> G](fa => Kleisli(_ => fa))

    implicit val liftLower: ContextualLiftLower[F, G, String] = ContextualLiftLower[F, G, String](lift, _ => lift)(
      λ[G ~> F](_.run(emptySpan)),
      name => λ[G ~> F](ga => ep.root(name).use(ga.run))
    )

    val liftedConfig: () => G[Either[ValidationErrors, Configuration.Config]] = () => lift(getConfig())

    eitherTComponents[G, F](liftedConfig).mapK(liftLower.lower).map {
      case (config, routes, registry, stats) =>
        (config, ep.liftT(routes), registry, stats)
    }
  }

  private def eitherTComponents[F[_]: ContextShift: Timer: Parallel, M[_]: Concurrent: ContextShift: Timer](
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]]
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
        tracedComponents[G, M](liftedConfig).map {
          case (config, routes, registry, stream) =>
            val r = Kleisli[OptionT[F, *], Request[F], Response[F]] { req =>
              OptionT(handleControlError[G](routes.run(req.mapK(lift)).value)).mapK(lower).map(_.mapK(lower))
            }

            (config, r, registry, stream)
        }
      }
      .mapK(lower)
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
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]]
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

    implicit val commandPayloadSerde: CommandSerde[F, CommandPayload] =
      CommandSerde[F, String].imap(CommandPayload)(_.hexValue)

    def notifyUpdate[A](topic: Topic[F, Boolean], topics: Topic[F, Boolean]*): A => F[Unit] =
      _ => (topic :: topics.toList).parTraverse(_.publish1(true)).void

    def makeRegistry: Resource[F, CollectorRegistry] =
      Resource.make[F, CollectorRegistry](Sync[F].delay {
        val registry = new CollectorRegistry(true)
        registry.register(new CacheCollector())
        registry
      })(r => Sync[F].delay(r.clear()))

    for {
      config <- Resource.liftF[F, Configuration.Config](configOrError(getConfig()))
      blocker <- Resource
        .make(Sync[F].delay(Executors.newCachedThreadPool()))(es => Sync[F].delay(es.shutdown()))
        .map(e => Blocker.liftExecutorService(e))
      registry <- makeRegistry
      activitiesUpdate <- Resource.liftF(Topic[F, Boolean](false))
      buttonsUpdate <- Resource.liftF(Topic[F, Boolean](false))
      remotesUpdate <- Resource.liftF(Topic[F, Boolean](false))
      roomsUpdate <- Resource.liftF(Topic[F, Boolean](false))
      statsSwitchUpdate <- Resource.liftF(Topic[F, Boolean](false))
      statsConfigUpdate <- Resource.liftF(Topic[F, Boolean](false))

      activityConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("activity"),
          config.config.pollInterval,
          blocker,
          config.config.writeTimeout
        )

      activityConfig <- ExtruderActivityConfigSource[F, G](
        activityConfigFileSource,
        config.config.activity,
        notifyUpdate(activitiesUpdate, roomsUpdate, statsConfigUpdate)
      )

      buttonConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("button"),
          config.config.pollInterval,
          blocker,
          config.config.writeTimeout
        )

      buttonConfig <- ExtruderButtonConfigSource[F, G](
        buttonConfigFileSource,
        config.config.button,
        notifyUpdate(buttonsUpdate, roomsUpdate, statsConfigUpdate)
      )

      remoteConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("remote"),
          config.config.pollInterval,
          blocker,
          config.config.writeTimeout
        )

      remoteConfig <- ExtruderRemoteConfigSource[F, G](
        remoteConfigFileSource,
        config.config.remote,
        notifyUpdate(remotesUpdate, roomsUpdate, statsConfigUpdate)
      )

      virtualSwitchConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("virtual-switch"),
          config.config.pollInterval,
          blocker,
          config.config.writeTimeout
        )

      virtualSwitchConfig <- ExtruderVirtualSwitchConfigSource[F, G](
        virtualSwitchConfigFileSource,
        config.config.virtualSwitch,
        notifyUpdate(remotesUpdate, roomsUpdate, statsSwitchUpdate)
      )

      multiSwitchConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.config.dir.resolve("multi-switch"),
          config.config.pollInterval,
          blocker,
          config.config.writeTimeout
        )

      multiSwitchConfig <- ExtruderMultiSwitchConfigSource[F, G](
        multiSwitchConfigFileSource,
        config.config.multiSwitch,
        notifyUpdate(remotesUpdate, roomsUpdate, statsSwitchUpdate)
      )

      activityStore <- Resource.liftF(FileActivityStore[F](config.stores.activityStore, blocker))
      macroStore <- Resource.liftF(FileMacroStore[F](config.stores.macroStore, blocker))
      commandStore <- Resource.liftF(
        FileRemoteCommandStore[F, CommandPayload](config.stores.remoteCommandStore, blocker)
      )

      switchStateFileStore <- FileSwitchStateStore.polling[F, G](
        config.stores.switchStateStore,
        config.stores.switchStatePolling,
        blocker,
        notifyUpdate(buttonsUpdate, remotesUpdate, statsSwitchUpdate)
      )

      rm2RemoteControls <- Resource.liftF(RmRemoteControls[F](config.rm.map(_.config), commandStore, blocker))

      hs100SwitchProvider <- HS100SwitchProvider[F, G](
        config.hs100.configs,
        config.hs100.polling,
        notifyUpdate(buttonsUpdate, remotesUpdate, statsSwitchUpdate),
        blocker
      )

      spSwitchProvider <- SpSwitchProvider[F, G](
        config.sp.configs,
        config.sp.polling,
        switchStateFileStore,
        blocker,
        notifyUpdate(buttonsUpdate, remotesUpdate, statsSwitchUpdate)
      )

      sonosComponents <- SonosComponents[F, G](
        config.sonos,
        () => notifyUpdate(buttonsUpdate, remotesUpdate, roomsUpdate, statsConfigUpdate, statsSwitchUpdate)(()),
        blocker,
        () => notifyUpdate(remotesUpdate, statsSwitchUpdate, statsConfigUpdate)(())
      )

      combinedActivityConfig = WritableConfigSource.combined(activityConfig, sonosComponents.activityConfig)
      combinedRemoteConfig = WritableConfigSource.combined(remoteConfig, sonosComponents.remoteConfig)
      remoteControls = RemoteControls
        .combined[F](rm2RemoteControls, RemoteControls[F](Map(config.sonos.remote -> sonosComponents.remote)))

      switchStateStore <- Resource.liftF(
        SwitchDependentStore.fromProvider[F](
          config.rm.flatMap(c => c.dependentSwitch.map(c.config.name -> _)).toMap,
          switchStateFileStore,
          SwitchProvider.combined(hs100SwitchProvider, spSwitchProvider)
        )
      )

      virtualSwitches <- SwitchesForRemote.polling[F](
        config.virtualSwitch,
        virtualSwitchConfig,
        rm2RemoteControls,
        switchStateStore,
        notifyUpdate(buttonsUpdate, remotesUpdate, statsSwitchUpdate)
      )

      combinedSwitchProvider = SwitchProvider
        .combined[F](hs100SwitchProvider, spSwitchProvider, sonosComponents.switches, virtualSwitches)

      multiSwitchProvider = MultiSwitchProvider[F](multiSwitchConfig, Switches[F](combinedSwitchProvider))

      switches = Switches[F](SwitchProvider.combined[F](combinedSwitchProvider, multiSwitchProvider))

      instrumentation <- StatsStream[F](
        config.stats,
        remoteControls,
        switches,
        combinedActivityConfig,
        buttonConfig,
        macroStore,
        remoteConfig,
        combinedSwitchProvider
      )(
        m =>
          Activity.dependsOnSwitch[F](
            config.activity.dependentSwitches,
            combinedSwitchProvider,
            activityStore,
            m,
            notifyUpdate(activitiesUpdate)
        ),
        (r, s) => Macro[F](macroStore, r, s)
      )(statsConfigUpdate, statsSwitchUpdate)

      l <- Resource.liftF(Slf4jLogger.fromName[F]("stats"))
    } yield {
      val validation =
        new ConfigValidation(combinedActivityConfig, instrumentation.remote, macroStore, instrumentation.switch)

      val configService =
        new ConfigService(
          combinedActivityConfig,
          buttonConfig,
          combinedRemoteConfig,
          macroStore,
          activityStore,
          switches,
          validation
        )

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
            activityConfig
          ).routes,
          "/config" -> new ConfigApi[F, G](
            configService,
            UpdateTopics(activitiesUpdate, buttonsUpdate, remotesUpdate, roomsUpdate)
          ).routes,
          "/" -> new ControllerUi[F](blocker).routes,
          "/" -> PrometheusExportService.service[F](registry)
        )

      val logger: Logger[G] = l.mapK(liftLower.lower)

      val stats: Stream[G, Unit] = {
        val fullStream =
          instrumentation.statsStream.translate(liftLower.lower).through(MetricsSink[G](registry, blocker))

        fullStream.handleErrorWith { th =>
          Stream.eval(logger.error(th)("Stats publish failed")).flatMap(_ => fullStream)
        }
      }
      (config.server, router, registry, stats)
    }
  }
}
