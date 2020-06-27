package io.janstenpickle.controller.kodi

import java.net.http.HttpClient
import java.util.concurrent.{Executor, Executors, ThreadFactory}

import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.{Blocker, Clock, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.mtl.ApplicativeHandle
import cats.mtl.implicits._
import cats.syntax.flatMap._
import cats.syntax.semigroup._
import cats.{~>, Applicative, ApplicativeError, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.`macro`.store.{MacroStore, TracedMacroStore}
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.advertiser.{Advertiser, Discoverer, JmDNSResource, ServiceType}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.trace.completer.jaeger.JaegerSpanCompleter
import io.janstenpickle.trace.natchez.CatsEffectTracer
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.discovery.config.CirceDiscoveryMappingConfigSource
import io.janstenpickle.controller.events.TopicEvents
import io.janstenpickle.controller.events.commands.EventCommands
import io.janstenpickle.controller.events.websocket.ClientWs
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.http4s.client.EitherTClient
import io.janstenpickle.controller.http4s.error.{ControlError, Handler}
import io.janstenpickle.controller.http4s.trace.implicits._
import io.janstenpickle.controller.plugin.api.PluginApi
import io.janstenpickle.controller.server.Server
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.trace.EmptyTrace
import io.janstenpickle.controller.trace.instances._
import io.janstenpickle.controller.trace.prometheus.PrometheusTracer
import io.janstenpickle.trace.SpanSampler
import io.prometheus.client.CollectorRegistry
import natchez.{EntryPoint, Kernel, Span, Trace}
import org.http4s.client.Client
import org.http4s.client.jdkhttpclient.JdkHttpClient
import org.http4s.client.middleware.{GZip, Metrics}
import org.http4s.metrics.prometheus.Prometheus
import org.http4s.{HttpRoutes, Request, Response}

object Module {
  private final val serviceName = "kodi"

  def registry[F[_]: Sync]: Resource[F, CollectorRegistry] =
    Resource.make[F, CollectorRegistry](Sync[F].delay {
      new CollectorRegistry(true)
    })(r => Sync[F].delay(r.clear()))

  def entryPoint[F[_]: Concurrent: ContextShift: Timer](
    registry: CollectorRegistry,
    blocker: Blocker
  ): Resource[F, EntryPoint[F]] =
    JaegerSpanCompleter[F](serviceName, blocker).flatMap { completer =>
      PrometheusTracer
        .entryPoint[F](serviceName, registry, blocker)
        .map(_ |+| CatsEffectTracer.entryPoint[F](SpanSampler.always, completer))
    }

  def httpClient[F[_]: ConcurrentEffect: ContextShift: Clock](
    registry: CollectorRegistry,
    blocker: Blocker
  ): Resource[F, Client[F]] = {
    def blockerExecutor(blocker: Blocker): Executor =
      new Executor {
        override def execute(command: Runnable): Unit =
          blocker.blockingContext.execute(command)
      }

    for {
      metrics <- Prometheus.metricsOps(registry, "org_http4s_client")
      client <- Resource.liftF {
        Sync[F].delay(JdkHttpClient[F](HttpClient.newBuilder().executor(blockerExecutor(blocker)).build()))
      }
    } yield GZip()(Metrics(metrics)(client))
  }

  def components[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    config: Configuration.Config
  ): Resource[F, (HttpRoutes[F], CollectorRegistry)] =
    for {
      blocker <- Blocker[F]

      reg <- registry[F]
      ep <- entryPoint[F](reg, blocker)
      client <- httpClient[F](reg, blocker)
      routes <- components[F](config, client, ep)
    } yield (routes, reg)

  def components[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    config: Configuration.Config,
    client: Client[F],
    ep: EntryPoint[F]
  ): Resource[F, HttpRoutes[F]] = {
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

    eitherTComponents[G, F](config, ep.lowerT(client))
      .mapK(liftLower.lower)
      .map { routes =>
        ep.liftT(routes)
      }
  }

  private def eitherTComponents[F[_]: ContextShift: Timer: Parallel, M[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config,
    client: Client[F],
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
      .liftF(Slf4jLogger.fromName[G]("Kodi Error"))
      .flatMap { implicit logger =>
        tracedComponents[G, M](config, EitherTClient[F, ControlError](client)).map { routes =>
          val r = Kleisli[OptionT[F, *], Request[F], Response[F]] { req =>
            OptionT(Handler.handleControlError[G](routes.run(req.mapK(lift)).value)).mapK(lower).map(_.mapK(lower))
          }

          r
        }
      }
      .mapK(lower)
  }

  private def tracedComponents[F[_]: Concurrent: ContextShift: Timer: Trace: Parallel: ErrorInterpreter, G[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config,
    client: Client[F]
  )(
    implicit ah: ApplicativeHandle[F, ControlError],
    liftLower: ContextualLiftLower[G, F, String],
    liftLowerContext: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, HttpRoutes[F]] = {
    def makeBlocker(name: String) =
      Blocker.fromExecutorService(Sync[F].delay(Executors.newCachedThreadPool(new ThreadFactory {
        def newThread(r: Runnable) = {
          val t = new Thread(r, s"$name-blocker")
          t.setDaemon(true)
          t
        }
      })))

    for {
      events <- Resource.liftF(TopicEvents[F])
      discoveryBlocker <- makeBlocker("discovery")
      workBlocker <- makeBlocker("work")

      fileSource <- ConfigFileSource
        .polling[F, G](
          config.dir.resolve("discovery-mapping"),
          config.polling.pollInterval,
          workBlocker,
          config.writeTimeout
        )

      discoverySource <- CirceDiscoveryMappingConfigSource[F, G](fileSource, config.polling, events.discovery.publisher)

      host <- Resource.liftF(Server.hostname[F](config.host))

      jmdns <- JmDNSResource[F](host)
      coordinator <- config.coordinator.fold(
        Resource
          .liftF(Discoverer.findService[F](jmdns, ServiceType.Coordinator))
          .map(services => Configuration.Coordinator(services.head.addresses.head, services.head.port))
      )(Resource.pure[F, Configuration.Coordinator])

      eventsState <- ClientWs.send[F, G](coordinator.host, coordinator.port, workBlocker, events)

      components <- KodiComponents[F, G](
        client,
        discoveryBlocker,
        config.kodi.copy(enabled = true),
        discoverySource,
        events.remote.publisher,
        events.switch.publisher,
        events.config.publisher,
        events.discovery.publisher,
      )

      _ <- Resource.liftF(eventsState.completeWithComponents(components, "kodi", events.source))

      _ <- RefreshListener[F](
        events.config.publisher,
        events.switch.publisher,
        events.switch.subscriberStream,
        events.remote.subscriberStream,
        components.switches,
        components.remotes,
        components.remoteConfig
      )

      switches = Switches[F](components.switches)

      macroStore = TracedMacroStore(MacroStore.fromReadOnlyConfigSource(components.macroConfig), "read-only")
      mac = Macro.conditional(macroStore, components.remotes, switches, events.`macro`.publisher)

      _ <- EventCommands[F, G](
        events.command.subscriberStream.filterEvent(_.source != events.source),
        Context.noop[F],
        mac,
        Activity.noop[F],
        components.remotes,
        components.rename,
      )

      _ <- Advertiser[F](jmdns, config.server.port, ServiceType.Plugin, NonEmptyString("Kodi"))
      api <- Resource.liftF(PluginApi[F, G](events, components, switches, mac))
    } yield api
  }
}
