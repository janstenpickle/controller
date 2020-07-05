package io.janstenpickle.controller.homekit

import java.util.UUID
import java.util.concurrent.{Executors, ThreadFactory}

import cats.data.{Kleisli, Reader}
import cats.effect.syntax.concurrent._
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, ExitCode, Resource, Sync, Timer}
import cats.syntax.semigroup._
import cats.{~>, Id, Parallel}
import fs2.Stream
import fs2.concurrent.Signal
import io.janstenpickle.controller.advertiser.{Discoverer, JmDNSResource, ServiceType}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.websocket.JavaWebsocket
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.{CommandEvent, SwitchEvent}
import io.janstenpickle.controller.server.Server
import io.janstenpickle.controller.trace.EmptyTrace
import io.janstenpickle.controller.trace.instances._
import io.janstenpickle.controller.trace.prometheus.PrometheusTracer
import io.janstenpickle.trace4cats.avro.AvroSpanCompleter
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.TraceProcess
import io.janstenpickle.trace4cats.natchez.Trace4CatsTracer
import io.prometheus.client.CollectorRegistry
import natchez.{EntryPoint, Kernel, Span, Trace}

import scala.concurrent.Future

object Module {
  type Homekit[F[_]] = Reader[(F ~> Future, F ~> Id, Signal[F, Boolean]), Stream[F, ExitCode]]

  private final val serviceName = "homekit"

  def registry[F[_]: Sync]: Resource[F, CollectorRegistry] =
    Resource.make[F, CollectorRegistry](Sync[F].delay {
      new CollectorRegistry(true)
    })(r => Sync[F].delay(r.clear()))

  def entryPoint[F[_]: Concurrent: ContextShift: Timer](
    registry: CollectorRegistry,
    blocker: Blocker
  ): Resource[F, EntryPoint[F]] =
    AvroSpanCompleter.udp[F](blocker, TraceProcess(serviceName)).flatMap { completer =>
      PrometheusTracer
        .entryPoint[F](serviceName, registry, blocker)
        .map(_ |+| Trace4CatsTracer.entryPoint[F](SpanSampler.always, completer))
    }

  def components[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    config: Configuration.Config
  ): Resource[F, (CollectorRegistry, Homekit[F])] =
    for {
      blocker <- Blocker[F]

      reg <- registry[F]
      ep <- entryPoint[F](reg, blocker)
      homekit <- components[F](config, ep)
    } yield (reg, homekit)

  def components[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    config: Configuration.Config,
    ep: EntryPoint[F]
  ): Resource[F, Homekit[F]] = {
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

    tracedComponents[G, F](config).mapK(liftLower.lower)
  }

  private def tracedComponents[F[_]: ContextShift: Timer: Trace: Parallel, G[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config
  )(
    implicit F: Concurrent[F],
    liftLower: ContextualLiftLower[G, F, String],
    liftLowerContext: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, Homekit[G]] = {
    def makeBlocker(name: String) =
      Blocker.fromExecutorService(Sync[F].delay(Executors.newCachedThreadPool(new ThreadFactory {
        def newThread(r: Runnable) = {
          val t = new Thread(r, s"$name-blocker")
          t.setDaemon(true)
          t
        }
      })))

    for {
      source <- Resource.liftF(Sync[F].delay(UUID.randomUUID().toString))
      switchEvents <- Resource.liftF(EventPubSub.topicNonBlocking[F, SwitchEvent](1000, source))
      commandEvents <- Resource.liftF(EventPubSub.topicNonBlocking[F, CommandEvent](50, source))

      workBlocker <- makeBlocker("work")

      _ <- switchEvents.subscriberStream.subscribeEvent
        .evalMap(e => F.delay(println(e)))
        .compile
        .drain
        .background

      host <- Resource.liftF(Server.hostname[F](config.host))

      jmdns <- JmDNSResource[F](host)
      coordinator <- config.coordinator.fold(
        Resource
          .liftF(Discoverer.findService[F](jmdns, ServiceType.Coordinator))
          .map(services => Configuration.Coordinator(services.head.addresses.head, services.head.port))
      )(Resource.pure[F, Configuration.Coordinator])

      _ <- JavaWebsocket
        .receive[F, G, SwitchEvent](coordinator.host, coordinator.port, "switch", workBlocker, switchEvents.publisher)
      _ <- JavaWebsocket.send[F, G, CommandEvent](
        coordinator.host,
        coordinator.port,
        "command",
        workBlocker,
        commandEvents.subscriberStream
      )

      homeKitSwitchEventSubscriber <- switchEvents.subscriberResource

      homekitConfigFileSource <- ConfigFileSource
        .polling[F, G](config.dir.resolve("homekit"), config.pollInterval, workBlocker, config.writeTimeout)

    } yield
      ControllerHomekitServer
        .stream[F, G](
          host,
          config.homekit,
          homekitConfigFileSource,
          homeKitSwitchEventSubscriber,
          commandEvents.publisher
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
  }
}
