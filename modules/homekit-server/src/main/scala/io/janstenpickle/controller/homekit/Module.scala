package io.janstenpickle.controller.homekit

import java.util.UUID
import java.util.concurrent.{Executors, ThreadFactory}
import cats.data.{Kleisli, Reader}
import cats.effect.syntax.concurrent._
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, ExitCode, Resource, Sync, Timer}
import cats.syntax.parallel._
import cats.syntax.semigroup._
import cats.{~>, Id, Parallel}
import fs2.Stream
import fs2.concurrent.Signal
import io.janstenpickle.controller.advertiser.{Discoverer, JmDNSResource, ServiceType}
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.websocket.JavaWebsocket
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.{CommandEvent, SwitchEvent}
import io.janstenpickle.controller.server.Server
import io.janstenpickle.controller.trace.prometheus.PrometheusSpanCompleter
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.avro.AvroSpanCompleter
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{EntryPoint, Trace}
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders, TraceProcess}
import io.prometheus.client.CollectorRegistry

import scala.concurrent.Future

object Module {
  type Homekit[F[_]] = Reader[(F ~> Future, F ~> Id, Signal[F, Boolean]), Stream[F, ExitCode]]

  private final val serviceName = "homekit"
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

    val lower = λ[G ~> F](ga => Span.noop[F].use(ga.run))

    tracedComponents[G, F](config, ep).mapK(lower)
  }

  private def tracedComponents[F[_]: ContextShift: Timer: Trace: Parallel, G[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config,
    ep: EntryPoint[G]
  )(implicit F: Concurrent[F], provide: Provide[G, F, Span[G]]): Resource[F, Homekit[G]] = {
    def makeBlocker(name: String) =
      Blocker.fromExecutorService(Sync[F].delay(Executors.newCachedThreadPool(new ThreadFactory {
        def newThread(r: Runnable) = {
          val t = new Thread(r, s"$name-blocker")
          t.setDaemon(true)
          t
        }
      })))

    val lower = λ[F ~> G](ga => ep.root("homekit").use(provide.provide(ga)))

    for {
      source <- Resource.eval(Sync[F].delay(UUID.randomUUID().toString))
      switchEvents <- Resource.eval(EventPubSub.topicNonBlocking[F, SwitchEvent](1000, source))
      commandEvents <- Resource.eval(EventPubSub.topicNonBlocking[F, CommandEvent](50, source))

      workBlocker <- makeBlocker("work")

      _ <- switchEvents.subscriberStream.subscribeEvent
        .evalMap(e => F.delay(println(e)))
        .compile
        .drain
        .background

      host <- Resource.eval(Server.hostname[F](config.host))

      jmdns <- JmDNSResource[F](host)
      coordinator <- config.coordinator.fold(
        Resource
          .liftF(Discoverer.findService[F](jmdns, ServiceType.Coordinator))
          .map(services => Configuration.Coordinator(services.head.addresses.head, services.head.port))
      )(Resource.pure[F, Configuration.Coordinator])

      _ <- JavaWebsocket
        .receive[F, G, SwitchEvent, Span[G]](
          coordinator.host,
          coordinator.port,
          "switch",
          workBlocker,
          switchEvents.publisher,
          ep.toKleisli.local { name: String =>
            (name, SpanKind.Consumer, TraceHeaders.empty)
          }
        )
      _ <- JavaWebsocket.send[F, G, CommandEvent, Span[G]](
        coordinator.host,
        coordinator.port,
        "command",
        workBlocker,
        commandEvents.subscriberStream,
        ep.toKleisli.local { name: String =>
          (name, SpanKind.Producer, TraceHeaders.empty)
        }
      )

      homeKitSwitchEventSubscriber <- switchEvents.subscriberResource

      homekitConfigFileSource <- ConfigFileSource
        .polling[F, G](
          config.dir.resolve("homekit"),
          config.pollInterval,
          workBlocker,
          config.writeTimeout,
          ep.toKleisli.local { name =>
            (name, SpanKind.Internal, TraceHeaders.empty)
          }
        )

    } yield
      ControllerHomekitServer
        .stream[F, G](
          host,
          config.homekit,
          homekitConfigFileSource,
          homeKitSwitchEventSubscriber,
          commandEvents.publisher,
          ep.toKleisli.local { case (name, kind) => (name, kind, TraceHeaders.empty) }
        )
        .local[(G ~> Future, G ~> Id, Signal[G, Boolean])] {
          case (fkFuture, fk, signal) =>
            (fkFuture.compose(lower), fk.compose(lower), new Signal[F, Boolean] {
              override def discrete: Stream[F, Boolean] = signal.discrete.translate(provide.liftK)
              override def continuous: Stream[F, Boolean] = signal.continuous.translate(provide.liftK)
              override def get: F[Boolean] = provide.lift(signal.get)
            })
        }
        .map(_.translate(lower))
  }
}
