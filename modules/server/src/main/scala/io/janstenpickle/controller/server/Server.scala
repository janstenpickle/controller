package io.janstenpickle.controller.server

import java.net.InetAddress
import java.util.concurrent.{Executors, ThreadFactory}

import cats.effect.concurrent.Ref
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, Resource, Sync, Timer}
import cats.syntax.flatMap._
import cats.syntax.semigroupk._
import cats.{Applicative, Eq, MonadError}
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import extruder.cats.effect.EffectValidation
import extruder.core.{Decoder, Settings, ValidationErrorsToThrowable}
import extruder.data.ValidationErrors
import fs2.Stream
import io.janstenpickle.controller.server.Reloader.ExitSignal
import io.prometheus.client.CollectorRegistry
import org.http4s.HttpRoutes
import org.http4s.metrics.prometheus.{Prometheus, PrometheusExportService}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.{CORS, CORSConfig, GZip, Metrics}
import org.http4s.syntax.all._

import scala.concurrent.duration._

object Server {
  case class Config(
    host: NonEmptyString = NonEmptyString("0.0.0.0"),
    port: PortNumber = PortNumber(8090),
    responseHeaderTimeout: FiniteDuration = 4.seconds,
    idleTimeout: FiniteDuration = 5.seconds
  )

  def configOrError[F[_], A](result: F[Either[ValidationErrors, A]])(implicit F: MonadError[F, Throwable]): F[A] =
    result.flatMap(
      _.fold[F[A]](
        errs => F.raiseError(ValidationErrorsToThrowable.defaultValidationErrorsThrowable.convertErrors(errs)),
        F.pure
      )
    )

  def blocker[F[_]: Sync](name: String): Resource[F, Blocker] =
    Blocker.fromExecutorService(Sync[F].delay(Executors.newCachedThreadPool(new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = new Thread(r, s"$name-blocker")
        t.setDaemon(true)
        t
      }
    })))

  def hostname[F[_]: Sync](config: Option[NonEmptyString]): F[String] =
    config
      .map(_.value)
      .orElse(Option(System.getenv("HOSTNAME")).filter(_.nonEmpty))
      .fold(Sync[F].delay(InetAddress.getLocalHost.getHostName))(host => Applicative[F].pure(host))

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer, A <: ServerConfig: Eq](
    configFile: Option[String],
    services: (A, ExitSignal[F]) => Stream[F, (HttpRoutes[F], CollectorRegistry, Option[Stream[F, Unit]])]
  )(implicit decoder: Decoder[EffectValidation[F, *], Settings, A, com.typesafe.config.Config]): Stream[F, ExitCode] = {
    def server(
      config: Config,
      registry: CollectorRegistry,
      routes: HttpRoutes[F],
      blocker: Blocker,
      signal: ExitSignal[F]
    ): Stream[F, ExitCode] =
      for {
        _ <- Stream.resource(PrometheusExportService.addDefaults[F](registry))
        prometheus <- Stream.resource(Prometheus.metricsOps(registry))
        instrumentedRoutes = Metrics(prometheus)(routes)
        exit <- Stream.eval(Ref[F].of(ExitCode.Success))
        corsConfig = CORSConfig(
          anyOrigin = true,
          allowedOrigins = _ => true,
          allowCredentials = true,
          maxAge = 1.day.toSeconds
        )
        exitCode <- BlazeServerBuilder[F](blocker.blockingContext)
          .bindHttp(config.port.value, config.host.value)
          .withResponseHeaderTimeout(config.responseHeaderTimeout)
          .withIdleTimeout(config.idleTimeout)
          .withHttpApp(
            CORS(GZip((instrumentedRoutes <+> PrometheusExportService.service[F](registry)).orNotFound), corsConfig)
          )
          .serveWhile(signal, exit)
      } yield exitCode

    Reloader[F] { (reload, signal) =>
      for {
        blocker <- Stream.resource(
          Resource
            .make(Sync[F].delay(Executors.newCachedThreadPool()))(es => Sync[F].delay(es.shutdown()))
            .map(e => Blocker.liftExecutorService(e))
        )
        getConfig <- Stream.resource(
          ConfigPoller[F, A](configFile, blocker, (_, _) => Sync[F].suspend(reload.set(true)))
        )
        conf <- Stream.eval(configOrError(getConfig()))
        (routes, registry, concurrent) <- services(conf, signal)

        svr = server(conf.server, registry, routes, blocker, signal)
        exitCode <- concurrent.fold(svr)(svr.concurrently)
      } yield exitCode
    }
  }
}
