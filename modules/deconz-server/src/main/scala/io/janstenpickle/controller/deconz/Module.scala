package io.janstenpickle.controller.deconz

import java.util.UUID
import java.util.concurrent.{Executors, ThreadFactory}

import cats.data.Kleisli
import cats.effect.syntax.concurrent._
import cats.syntax.parallel._
import cats.syntax.semigroup._
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.{~>, Parallel}
import io.janstenpickle.controller.advertiser.{Discoverer, JmDNSResource, ServiceType}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.websocket.JavaWebsocket
import io.janstenpickle.controller.model.event.CommandEvent
import io.janstenpickle.controller.server.Server
import io.janstenpickle.controller.trace.prometheus.PrometheusSpanCompleter
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.avro.AvroSpanCompleter
import io.janstenpickle.trace4cats.inject.{EntryPoint, Trace}
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.{SpanKind, TraceProcess}
import io.prometheus.client.CollectorRegistry

object Module {
  private final val serviceName = "deconz"
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
  ): Resource[F, CollectorRegistry] =
    for {
      blocker <- Blocker[F]
      reg <- registry[F]
      ep <- entryPoint[F](reg, blocker)
      _ <- components[F](config, ep)
    } yield reg

  def components[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    config: Configuration.Config,
    ep: EntryPoint[F]
  ): Resource[F, Unit] = {
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

    tracedComponents[G, F](config)
      .mapK(liftLower.lower)
  }

  private def tracedComponents[F[_]: ContextShift: Timer: Trace: Parallel, G[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Config
  )(
    implicit F: Concurrent[F],
    liftLower: ContextualLiftLower[G, F, String],
    liftLowerContext: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, Unit] = {
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
      commandEvents <- Resource.liftF(EventPubSub.topicNonBlocking[F, CommandEvent](50, source))

      _ <- commandEvents.subscriberStream.subscribe.evalMap(e => F.delay(println(e))).compile.drain.background

      workBlocker <- makeBlocker("work")

      host <- Resource.liftF(Server.hostname[F](config.host))

      jmdns <- JmDNSResource[F](host)
      coordinator <- config.coordinator.fold(
        Resource
          .liftF(Discoverer.findService[F](jmdns, ServiceType.Coordinator))
          .map(services => Configuration.Coordinator(services.head.addresses.head, services.head.port))
      )(Resource.pure[F, Configuration.Coordinator])

      _ <- JavaWebsocket.send[F, G, CommandEvent](
        coordinator.host,
        coordinator.port,
        "command",
        workBlocker,
        commandEvents.subscriberStream
      )

      _ <- DeconzBridge[F, G](config.deconz, commandEvents.publisher, workBlocker)

    } yield ()
  }
}
