package io.janstenpickle.controller.broadlink

import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.kernel.Async
import cats.effect.{Concurrent, Resource, Sync}
import cats.mtl.ApplicativeHandle
import cats.mtl.implicits._
import cats.syntax.flatMap._
import cats.syntax.parallel._
import cats.syntax.semigroup._
import cats.{Applicative, ApplicativeError, Parallel, ~>}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.`macro`.store.{MacroStore, TracedMacroStore}
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.advertiser.{Advertiser, Discoverer, JmDNSResource, ServiceType}
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.discovery.config.CirceDiscoveryMappingConfigSource
import io.janstenpickle.controller.events.TopicEvents
import io.janstenpickle.controller.events.commands.EventCommands
import io.janstenpickle.controller.events.websocket.ClientWs
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.http4s.client.EitherTClient
import io.janstenpickle.controller.http4s.error.{ControlError, Handler}
import io.janstenpickle.controller.plugin.api.PluginApi
import io.janstenpickle.controller.remote.config.CirceRemoteCommandConfigSource
import io.janstenpickle.controller.remote.store.{RemoteCommandStore, TracedRemoteCommandStore}
import io.janstenpickle.controller.server.Server
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switches.store.{SwitchStateStore, TracedSwitchStateStore}
import io.janstenpickle.controller.trace.prometheus.PrometheusSpanCompleter
import io.janstenpickle.switches.config.CirceSwitchStateConfigSource
import io.janstenpickle.trace4cats.avro.AvroSpanCompleter
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.http4s.client.syntax._
import io.janstenpickle.trace4cats.http4s.server.syntax._
import io.janstenpickle.trace4cats.inject.{EntryPoint, ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.AttributeValue.LongValue
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders, TraceProcess}
import io.janstenpickle.trace4cats.{ErrorHandler, Span}
import io.prometheus.client.CollectorRegistry
import org.http4s.client.Client
import org.http4s.client.middleware.{GZip, Metrics}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.metrics.prometheus.Prometheus
import org.http4s.{HttpRoutes, Request, Response}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Module {
  private final val serviceName = "broadlink"
  private final val process = TraceProcess(serviceName)

  def registry[F[_]: Sync]: Resource[F, CollectorRegistry] =
    Resource.make[F, CollectorRegistry](Sync[F].delay {
      new CollectorRegistry(true)
    })(r => Sync[F].delay(r.clear()))

  def entryPoint[F[_]: Async: Parallel](registry: CollectorRegistry): Resource[F, EntryPoint[F]] =
    (AvroSpanCompleter.udp[F](process), PrometheusSpanCompleter[F](registry, process))
      .parMapN(_ |+| _)
      .map { completer =>
        EntryPoint[F](SpanSampler.always, completer)
      }

  def httpClient[F[_]: Async](registry: CollectorRegistry, ): Resource[F, Client[F]] =
    for {
      metrics <- Prometheus.metricsOps(registry, "org_http4s_client")
      client <- EmberClientBuilder.default[F].build
    } yield GZip()(Metrics(metrics)(client))

  def components[F[_]: Async: Parallel](
    config: Configuration.Config
  ): Resource[F, (HttpRoutes[F], CollectorRegistry)] =
    for {
      reg <- registry[F]
      ep <- entryPoint[F](reg)
      client <- httpClient[F](reg)
      routes <- components[F](config, client, ep)
    } yield (routes, reg)

  def components[F[_]: Async: Parallel](
    config: Configuration.Config,
    client: Client[F],
    ep: EntryPoint[F]
  ): Resource[F, HttpRoutes[F]] = {
    type G[A] = Kleisli[F, Span[F], A]

    val lower = λ[G ~> F](ga => Span.noop[F].use(ga.run))

    eitherTComponents[G, F](config, client.liftTrace(), ep)
      .mapK(lower)
      .map(_.inject(ep))
  }

  private def eitherTComponents[F[_]: Async: Parallel, M[_]: Async](
    config: Configuration.Config,
    client: Client[F],
    ep: EntryPoint[M]
  )(implicit F: Concurrent[F], trace: Trace[F], pv: Provide[M, F, Span[M]]): Resource[F, HttpRoutes[F]] = {
    type G[A] = EitherT[F, ControlError, A]

    val lift = λ[F ~> G](EitherT.liftF(_))

    val lower =
      λ[G ~> F](ga => ga.value.flatMap(_.fold(ApplicativeError[F, Throwable].raiseError, Applicative[F].pure)))

    implicit val provide: Provide[M, G, Span[M]] = pv.imapK(lift, lower)

    Resource
      .eval(Slf4jLogger.fromName[G]("Broadlink Error"))
      .flatMap { implicit logger =>
        tracedComponents[G, M](config, EitherTClient[F, ControlError](client), ep).map { routes =>
          val r = Kleisli[OptionT[F, *], Request[F], Response[F]] { req =>
            OptionT(Handler.handleControlError[G](routes.run(req.mapK(lift)).value)).mapK(lower).map(_.mapK(lower))
          }

          r
        }
      }
      .mapK(lower)
  }

  private def tracedComponents[F[_]: Async: Trace: Parallel: ErrorInterpreter, G[_]: Async](
    config: Configuration.Config,
    client: Client[F],
    ep: EntryPoint[G]
  )(implicit ah: ApplicativeHandle[F, ControlError], provide: Provide[G, F, Span[G]]): Resource[F, HttpRoutes[F]] = {

    val k: ResourceKleisli[G, SpanName, Span[G]] =
      ep.toKleisli.local(name => (name, SpanKind.Internal, TraceHeaders.empty, ErrorHandler.empty))

    def fileSource(name: String): Resource[F, ConfigFileSource[F]] =
      ConfigFileSource
        .polling[F, G](config.dir.resolve(name), config.polling.pollInterval, config.writeTimeout, k)

    for {
      events <- Resource.eval(TopicEvents[F])

      remoteCommandSource <- fileSource("remote-command").flatMap(
        CirceRemoteCommandConfigSource[F, G](_, config.polling, k)
      )

      switchStateSource <- fileSource("switch-state")
        .flatMap(CirceSwitchStateConfigSource[F, G](_, config.polling, events.switch.publisher.narrow, k))

      discoverySource <- fileSource("discovery-mapping").flatMap(
        CirceDiscoveryMappingConfigSource[F, G](_, config.polling, events.discovery.publisher, k)
      )

//      githubRemoteConfigSource <- GithubRemoteCommandConfigSource[F, G](
//        client,
//        config.githubRemoteCommands,
//        (_, _) => Applicative[F].unit
//      )

      commandStore = TracedRemoteCommandStore(
        RemoteCommandStore.fromConfigSource(
          remoteCommandSource // WritableConfigSource.combined(remoteCommandSource, githubRemoteConfigSource)
        ),
        "config",
        "path" -> config.dir.resolve("remote-command").toString,
        "timeout" -> LongValue(config.writeTimeout.toMillis)
      )

      switchStateFileStore = TracedSwitchStateStore(
        SwitchStateStore.fromConfigSource(switchStateSource),
        "config",
        "path" -> config.dir.resolve("switch-state").toString,
        "timeout" -> LongValue(config.writeTimeout.toMillis)
      )

      host <- Resource.eval(Server.hostname[F](config.host))

      jmdns <- JmDNSResource[F](host)
      coordinator <- config.coordinator.fold(
        Resource
          .eval(Discoverer.findService[F](jmdns, ServiceType.Coordinator))
          .map(services => Configuration.Coordinator(services.head.addresses.head, services.head.port))
      )(Resource.pure[F, Configuration.Coordinator])

      eventsState <- ClientWs.send[F, G, Span[G]](
        coordinator.host,
        coordinator.port,
        events,
        ep.toKleisli.local(name => (name, SpanKind.Producer, TraceHeaders.empty, ErrorHandler.empty))
      )

      components <- BroadlinkComponents[F, G](
        config.broadlink.copy(enabled = true),
        commandStore,
        switchStateFileStore,
        discoverySource,
        events.remote.publisher,
        events.switch.publisher,
        events.config.publisher,
        events.discovery.publisher,
        ep.toKleisli.local(name => (name, SpanKind.Internal, TraceHeaders.empty, ErrorHandler.empty))
      )

      _ <- Resource.eval(eventsState.completeWithComponents(components, "broadlink", events.source))

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
        ep.toKleisli.local { case (name, headers) => (name, SpanKind.Consumer, TraceHeaders(headers), ErrorHandler.empty) }
      )

      _ <- Advertiser[F](jmdns, config.server.port, ServiceType.Plugin, NonEmptyString("Broadlink"))
      api <- Resource.eval(
        PluginApi[F, G](
          events,
          components,
          switches,
          mac,
          ep.toKleisli.local(name => (name, SpanKind.Server, TraceHeaders.empty, ErrorHandler.empty))
        )
      )
    } yield api
  }
}
