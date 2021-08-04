package io.janstenpickle.controller.homekit

import cats.data.Reader
import cats.effect.kernel.Async
import cats.effect.{ExitCode, Resource}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Id}
import eu.timepit.refined.auto._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.concurrent.Signal
import io.github.hapjava.server.impl.HomekitServer
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.{CommandEvent, SwitchEvent}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, Trace}
import io.janstenpickle.trace4cats.model.SpanKind
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.InetAddress
import scala.concurrent.Future
import scala.concurrent.duration._

object ControllerHomekitServer {

  case class Config(
    label: NonEmptyString = NonEmptyString("Controller"),
    port: PortNumber = PortNumber(8091),
    threadCount: Option[PosInt],
    auth: ControllerHomekitAuthInfo.Config
  )

  def create[F[_]: Trace, G[_]: Async](
    host: String,
    config: Config,
    configFile: ConfigFileSource[F],
    switchEvents: EventSubscriber[F, SwitchEvent],
    commands: EventPublisher[F, CommandEvent],
    fkFuture: F ~> Future,
    fk: F ~> Id,
    exitSignal: Signal[F, Boolean],
    k: ResourceKleisli[G, (String, SpanKind), Span[G]]
  )(implicit F: Async[F], provide: Provide[G, F, Span[G]]): F[ExitCode] =
    (for {
      address <- Resource.eval[F, InetAddress](F.blocking(InetAddress.getByName(host)))
      authInfo <- ControllerHomekitAuthInfo[F, G](
        configFile,
        config.auth,
        fk,
        k.local(name => name -> SpanKind.Internal)
      )

      server <- Resource.make[F, HomekitServer](
        F.delay(
          config.threadCount.fold(new HomekitServer(address, config.port))(new HomekitServer(address, config.port, _))
        )
      )(s => F.delay(s.stop()))

      root <- Resource.make(
        F.delay(server.createBridge(authInfo, config.label, "controller", "controller", "1", "1", "1"))
          .flatTap(r => F.delay(r.start()))
      )(r => F.delay(r.stop()))

      _ <- ControllerAccessories[F, G](root, switchEvents, commands, fkFuture, fk, k)
    } yield ())
      .use(
        _ => exitSignal.discrete.map(if (_) None else Some(ExitCode.Success)).unNoneTerminate.compile.toList.map(_.head)
      )

  private def streamLoop[F[_]: Async: Trace, G[_]: Async](
    host: String,
    config: Config,
    configFile: ConfigFileSource[F],
    switchEvents: EventSubscriber[F, SwitchEvent],
    commands: EventPublisher[F, CommandEvent],
    fkFuture: F ~> Future,
    fk: F ~> Id,
    exitSignal: Signal[F, Boolean],
    logger: Logger[F],
    k: ResourceKleisli[G, (String, SpanKind), Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Stream[F, ExitCode] =
    Stream
      .eval(create[F, G](host, config, configFile, switchEvents, commands, fkFuture, fk, exitSignal, k))
      .handleErrorWith { th =>
        Stream.eval(logger.error(th)("Homekit failed")) >> Stream
          .sleep[F](10.seconds) >> streamLoop[F, G](
          host,
          config,
          configFile,
          switchEvents,
          commands,
          fkFuture,
          fk,
          exitSignal,
          logger,
          k
        )
      }

  def stream[F[_]: Async: Trace, G[_]: Async](
    host: String,
    config: Config,
    configFile: ConfigFileSource[F],
    switchEvents: EventSubscriber[F, SwitchEvent],
    commands: EventPublisher[F, CommandEvent],
    k: ResourceKleisli[G, (String, SpanKind), Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Reader[(F ~> Future, F ~> Id, Signal[F, Boolean]), Stream[F, ExitCode]] =
    Reader {
      case (fkFuture, fk, exitSignal) =>
        for {
          logger <- Stream.eval(Slf4jLogger.create[F])
          _ <- Stream.eval(logger.info("Starting homekit"))
          exitCode <- streamLoop[F, G](
            host,
            config,
            configFile,
            switchEvents,
            commands,
            fkFuture,
            fk,
            exitSignal,
            logger,
            k
          )
        } yield exitCode
    }
}
