package io.janstenpickle.controller.api

import cats.Parallel
import cats.effect._
import cats.effect.concurrent.Ref
import extruder.data.ValidationErrors
import fs2.Stream
import io.janstenpickle.controller.api.Reloader.ExitSignal
import io.janstenpickle.controller.api.config.{ConfigPoller, Configuration}
import org.http4s.metrics.prometheus.Prometheus
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.{CORS, CORSConfig, GZip, Metrics}
import org.http4s.syntax.all._

import scala.concurrent.duration._

object Server extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    new Server[IO](args.headOption).run.compile.toList.map(_.head)
}

class Server[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](configFile: Option[String]) {
  val run: Stream[F, ExitCode] = Reloader[F] { (reload, signal) =>
    for {
      getConfig <- Stream.resource(ConfigPoller[F](configFile, _ => Sync[F].suspend(reload.set(true))))
      exitCode <- server(getConfig, signal)
    } yield exitCode
  }

  private def server(
    getConfig: () => F[Either[ValidationErrors, Configuration.Config]],
    signal: ExitSignal[F]
  ): Stream[F, ExitCode] =
    for {
      components <- Stream.resource(Module.components(getConfig))
      (config, routes, registry, stats) = components
      prometheus <- Stream.eval(Prometheus(registry))
      instrumentedRoutes = Metrics(prometheus)(routes)
      exit <- Stream.eval(Ref[F].of(ExitCode.Success))
      corsConfig = CORSConfig(anyOrigin = true, allowCredentials = false, maxAge = 1.day.toSeconds)
      exitCode <- BlazeServerBuilder[F]
        .bindHttp(config.port.value, config.host.value)
        .withHttpApp(GZip(CORS(instrumentedRoutes.orNotFound, corsConfig)))
        .serveWhile(signal, exit)
        .concurrently(stats)
    } yield exitCode

}
