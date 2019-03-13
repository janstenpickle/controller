package io.janstenpickle.controller.api

import cats.effect._
import eu.timepit.refined.types.numeric.PosInt
import fs2.Stream
import io.janstenpickle.controller.stats.Stats
import io.janstenpickle.controller.stats.prometheus.{MetricsService, MetricsSink}
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.{CORS, CORSConfig}
import org.http4s.syntax.all._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object Server extends IOApp {
  val server = new Server[IO]
  override def run(args: List[String]): IO[ExitCode] =
    server.run.compile.toList.map(_.head)
}

class Server[F[_]: ConcurrentEffect: ContextShift: Timer] extends Module[F] {
  def stats(statsStream: Stream[F, Stats], ec: ExecutionContext): Stream[F, Unit] = {
    val fullStream = statsStream.through(MetricsSink[F](ec))

    fullStream.handleErrorWith { th =>
      Stream(th.printStackTrace()).flatMap(_ => fullStream)
    }
  }

  val run: Stream[F, ExitCode] = components.translate(translateF).flatMap {
    case (server, activity, macros, remotes, switches, activityConfig, configView, ec, updateTopics, statsStream) =>
      val corsConfig = CORSConfig(anyOrigin = true, allowCredentials = false, maxAge = 1.day.toSeconds)

      val router = Router(
        "/control/remote" -> new RemoteApi[F](remotes).routes,
        "/control/switch" -> new SwitchApi[F](switches).routes,
        "/control/macro" -> new MacroApi[F](macros).routes,
        "/control/activity" -> new ActivityApi[F](activity, activityConfig).routes,
        "/control/context" -> new ContextApi[F](activity, macros, remotes, activityConfig).routes,
        "/config" -> new ConfigApi[F](configView, updateTopics).routes,
        "/metrics" -> new MetricsService[F].routes,
        "/" -> new ControllerUi[F](ec).routes
      )
      BlazeServerBuilder[F]
        .bindHttp(server.port.value, server.host.value)
        .withHttpApp(CORS(router.orNotFound, corsConfig))
        .serve
        .concurrently(stats(statsStream, ec))
  }
}
