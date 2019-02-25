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
    case (server, configSource, view) =>
      val router = Router(
        "/control/remote" -> new RemoteApi[F](view).routes,
        "/control/switch" -> new SwitchApi[F](view).routes,
        "/control/macro" -> new MacroApi[F](view).routes,
        "/control/activity" -> new ActivityApi[F](view).routes,
        "/config" -> new ConfigApi[F](configSource).routes
      )

      BlazeServerBuilder[F]
        .bindHttp(server.port.value, server.host.value)
        .withHttpApp(router.orNotFound)
        .serve
  }
}
