package io.janstenpickle.controller.allinone.environment

import java.net.http.HttpClient
import java.util.concurrent.Executor

import cats.effect.{Blocker, Clock, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.syntax.semigroup._
import io.janstenpickle.controller.trace.instances._
import io.janstenpickle.controller.trace.prometheus.PrometheusTracer
import io.janstenpickle.trace4cats.avro.AvroSpanCompleter
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.TraceProcess
import io.janstenpickle.trace4cats.natchez.Trace4CatsTracer
import io.prometheus.client.CollectorRegistry
import natchez.EntryPoint
import org.http4s.client.Client
import org.http4s.client.jdkhttpclient.JdkHttpClient
import org.http4s.client.middleware.{GZip, Metrics}
import org.http4s.metrics.prometheus.Prometheus

object Resources {

  private final val serviceName = "controller"

  def registry[F[_]: Sync]: Resource[F, CollectorRegistry] =
    Resource.make[F, CollectorRegistry](Sync[F].delay {
      val registry = new CollectorRegistry(true)
      registry
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
}
