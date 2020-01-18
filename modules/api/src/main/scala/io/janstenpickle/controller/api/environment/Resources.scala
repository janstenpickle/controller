package io.janstenpickle.controller.api.environment

import java.net.http.HttpClient
import java.util.concurrent.Executor

import cats.effect.{Blocker, Clock, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.syntax.semigroup._
import io.jaegertracing.Configuration.{ReporterConfiguration, SamplerConfiguration}
import io.janstenpickle.controller.api.config.Configuration
import io.janstenpickle.controller.cache.monitoring.CacheCollector
import io.janstenpickle.controller.mqtt.Fs2MqttClient
import io.janstenpickle.controller.trace.instances._
import io.janstenpickle.controller.trace.prometheus.PrometheusTracer
import io.prometheus.client.CollectorRegistry
import natchez.EntryPoint
import natchez.jaeger.Jaeger
import org.http4s.client.Client
import org.http4s.client.jdkhttpclient.JdkHttpClient
import org.http4s.client.middleware.{GZip, Metrics}
import org.http4s.metrics.prometheus.Prometheus
object Resources {

  private final val serviceName = "controller"

  def registry[F[_]: Sync]: Resource[F, CollectorRegistry] =
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

  def mqttClient[F[_]: ConcurrentEffect: ContextShift: Timer](
    config: Configuration.Mqtt
  ): Resource[F, Option[Fs2MqttClient[F]]] =
    config.client.fold(Resource.pure[F, Option[Fs2MqttClient[F]]](None))(Fs2MqttClient[F](_).map(Some(_)))

}
