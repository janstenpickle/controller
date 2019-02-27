package io.janstenpickle.controller.api

import cats.effect._
import fs2.Stream
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.all._

object Server extends IOApp {
  val server = new Server[IO]
  override def run(args: List[String]): IO[ExitCode] =
    server.run.compile.toList.map(_.head)
}

class Server[F[_]: ConcurrentEffect: ContextShift: Timer] extends Module[F] {
  val run: Stream[F, ExitCode] = components.flatMap {
    case (server, activity, macros, remotes, switches, activityConfig, buttonConfig, remoteConfig) =>
      val router = Router(
        "/control/remote" -> new RemoteApi[F](remotes).routes,
        "/control/switch" -> new SwitchApi[F](switches).routes,
        "/control/macro" -> new MacroApi[F](macros).routes,
        "/control/activity" -> new ActivityApi[F](activity, activityConfig).routes,
        //     "/control/context" -> new ContextApi[F](activity, macros, remotes, activityConfig).routes,
        "/config" -> new ConfigApi[F](activityConfig, buttonConfig, remoteConfig).routes
      )

      BlazeServerBuilder[F]
        .bindHttp(server.port.value, server.host.value)
        .withHttpApp(router.orNotFound)
        .serve
  }
}
