package io.janstenpickle.controller.broadlink

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
import io.janstenpickle.controller.configsource.WritableConfigSource
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
import io.janstenpickle.controller.remote.config.CirceRemoteCommandConfigSource
import io.janstenpickle.controller.remote.store.{RemoteCommandStore, TracedRemoteCommandStore}
import io.janstenpickle.controller.remotecontrol.git.GithubRemoteCommandConfigSource
import io.janstenpickle.controller.server.Server
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switches.store.{SwitchStateStore, TracedSwitchStateStore}
import io.janstenpickle.controller.trace.EmptyTrace
import io.janstenpickle.controller.trace.instances._
import io.janstenpickle.controller.trace.prometheus.PrometheusTracer
import io.janstenpickle.switches.config.CirceSwitchStateConfigSource
import io.janstenpickle.trace.SpanSampler
import io.janstenpickle.trace.completer.jaeger.JaegerSpanCompleter
import io.janstenpickle.trace.natchez.CatsEffectTracer
import io.prometheus.client.CollectorRegistry
import natchez.TraceValue.NumberValue
import natchez.{EntryPoint, Kernel, Span, Trace}
import org.http4s.client.Client
import org.http4s.client.middleware.{GZip, Metrics}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.metrics.prometheus.Prometheus
import org.http4s.{HttpRoutes, Request, Response}

object Module {
  private final val serviceName = "broadlink"

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

  def httpClient[F[_]: ConcurrentEffect: ContextShift: Timer](
    registry: CollectorRegistry,
    blocker: Blocker
  ): Resource[F, Client[F]] =
    for {
      metrics <- Prometheus.metricsOps(registry, "org_http4s_client")
      client <- EmberClientBuilder.default[F].withBlocker(blocker).build
    } yield GZip()(Metrics(metrics)(client))

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
      .liftF(Slf4jLogger.fromName[G]("Broadlink Error"))
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

    def fileSource(name: String, blocker: Blocker): Resource[F, ConfigFileSource[F]] =
      ConfigFileSource
        .polling[F, G](config.dir.resolve(name), config.polling.pollInterval, blocker, config.writeTimeout)

    for {
      events <- Resource.liftF(TopicEvents[F])
      discoveryBlocker <- makeBlocker("discovery")
      workBlocker <- makeBlocker("work")

      remoteCommandSource <- fileSource("remote-command", workBlocker).flatMap(
        CirceRemoteCommandConfigSource[F, G](_, config.polling)
      )

      switchStateSource <- fileSource("switch-state", workBlocker)
        .flatMap(CirceSwitchStateConfigSource[F, G](_, config.polling, events.switch.publisher.narrow))

      discoverySource <- fileSource("discovery-mapping", workBlocker).flatMap(
        CirceDiscoveryMappingConfigSource[F, G](_, config.polling, events.discovery.publisher)
      )

      githubRemoteConfigSource <- GithubRemoteCommandConfigSource[F, G](
        client,
        config.githubRemoteCommands,
        (_, _) => Applicative[F].unit
      )

      commandStore = TracedRemoteCommandStore(
        RemoteCommandStore.fromConfigSource(
          WritableConfigSource.combined(remoteCommandSource, githubRemoteConfigSource)
        ),
        "config",
        "path" -> config.dir.resolve("remote-command").toString,
        "timeout" -> NumberValue(config.writeTimeout.toMillis)
      )

      switchStateFileStore = TracedSwitchStateStore(
        SwitchStateStore.fromConfigSource(switchStateSource),
        "config",
        "path" -> config.dir.resolve("switch-state").toString,
        "timeout" -> NumberValue(config.writeTimeout.toMillis)
      )

      host <- Resource.liftF(Server.hostname[F](config.host))

      jmdns <- JmDNSResource[F](host)
      coordinator <- config.coordinator.fold(
        Resource
          .liftF(Discoverer.findService[F](jmdns, ServiceType.Coordinator))
          .map(services => Configuration.Coordinator(services.head.addresses.head, services.head.port))
      )(Resource.pure[F, Configuration.Coordinator])

      eventsState <- ClientWs.send[F, G](coordinator.host, coordinator.port, workBlocker, events)

      components <- BroadlinkComponents[F, G](
        config.broadlink.copy(enabled = true),
        commandStore,
        switchStateFileStore,
        discoverySource,
        workBlocker,
        discoveryBlocker,
        events.remote.publisher,
        events.switch.publisher,
        events.config.publisher,
        events.discovery.publisher
      )

      _ <- Resource.liftF(eventsState.completeWithComponents(components, "broadlink", events.source))

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

      _ <- Advertiser[F](jmdns, config.server.port, ServiceType.Plugin, NonEmptyString("Broadlink"))
      api <- Resource.liftF(PluginApi[F, G](events, components, switches, mac))
    } yield api
  }
}
