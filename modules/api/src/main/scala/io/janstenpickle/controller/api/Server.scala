package io.janstenpickle.controller.api

import cats.effect._
import cats.effect.concurrent.Ref
import cats.{~>, Id, Parallel}
import extruder.data.ValidationErrors
import fs2.Stream
import io.janstenpickle.controller.api.environment.Module
import io.janstenpickle.controller.api.Reloader.ExitSignal
import io.janstenpickle.controller.api.config.{ConfigPoller, Configuration}
import org.http4s.metrics.prometheus.Prometheus
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.{CORS, CORSConfig, GZip, Metrics}
import org.http4s.syntax.all._

import scala.concurrent.Future
import scala.concurrent.duration._

object Server extends IOApp {

  private val fkFuture: IO ~> Future = λ[IO ~> Future](_.unsafeToFuture())
  private val fk: IO ~> Id = λ[IO ~> Id](_.unsafeRunSync())

  override def run(args: List[String]): IO[ExitCode] =
    new Server[IO](args.headOption, fkFuture, fk).run.compile.toList.map(_.head)
}

class Server[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
  configFile: Option[String],
  fkFuture: F ~> Future,
  fk: F ~> Id
) {
  val run: Stream[F, ExitCode] = Reloader[F] { (reload, signal) =>
    for {
      getConfig <- Stream.resource(ConfigPoller[F](configFile, (_, _) => Sync[F].suspend(reload.set(true))))
      exitCode <- server(getConfig, signal)
    } yield exitCode
  }

  private def server(
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]],
    signal: ExitSignal[F]
  ): Stream[F, ExitCode] =
    for {
      components <- Stream.resource(Module.components(getConfig))
      (config, routes, registry, homekit) = components
      prometheus <- Stream.resource(Prometheus.metricsOps(registry))
      instrumentedRoutes = Metrics(prometheus)(routes)
      exit <- Stream.eval(Ref[F].of(ExitCode.Success))
      corsConfig = CORSConfig(
        anyOrigin = true,
        allowedOrigins = _ => true,
        allowCredentials = true,
        maxAge = 1.day.toSeconds
      )
      exitCode <- BlazeServerBuilder[F]
        .bindHttp(config.port.value, config.host.value)
        .withResponseHeaderTimeout(config.responseHeaderTimeout)
        .withIdleTimeout(config.idleTimeout)
        .withHttpApp(CORS(GZip(instrumentedRoutes.orNotFound), corsConfig))
        .serveWhile(signal, exit)
        .concurrently(homekit(fkFuture, fk, signal))
    } yield exitCode

}
