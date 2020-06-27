package io.janstenpickle.controller.homekit

import java.net.InetAddress
import java.util.concurrent.{Executors, ThreadFactory}

import cats.data.Reader
import cats.effect.{Blocker, Concurrent, ContextShift, ExitCode, Resource, Sync, Timer}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Id}
import eu.timepit.refined.auto._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.concurrent.Signal
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.hapjava.HomekitServer
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.{EventPubSub, EventPublisher, EventSubscriber}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.{CommandEvent, SwitchEvent}
import natchez.Trace

import scala.concurrent.Future
import scala.concurrent.duration._

object ControllerHomekitServer {

  case class Config(
    label: NonEmptyString = NonEmptyString("Controller"),
    port: PortNumber = PortNumber(8091),
    threadCount: Option[PosInt],
    auth: ControllerHomekitAuthInfo.Config
  )

  def create[F[_]: Timer: ContextShift: Trace, G[_]: Timer: Concurrent](
    host: String,
    config: Config,
    configFile: ConfigFileSource[F],
    switchEvents: EventSubscriber[F, SwitchEvent],
    commands: EventPublisher[F, CommandEvent],
    blocker: Blocker,
    fkFuture: F ~> Future,
    fk: F ~> Id,
    exitSignal: Signal[F, Boolean]
  )(implicit F: Concurrent[F], liftLower: ContextualLiftLower[G, F, String]): F[ExitCode] =
    (for {
      address <- Resource.liftF[F, InetAddress](F.delay(InetAddress.getByName(host)))
      authInfo <- ControllerHomekitAuthInfo[F, G](configFile, config.auth, fk)

      server <- Resource.make[F, HomekitServer](
        F.delay(
          config.threadCount.fold(new HomekitServer(address, config.port))(new HomekitServer(address, config.port, _))
        )
      )(s => F.delay(s.stop()))

      root <- Resource.make(
        F.delay(server.createBridge(authInfo, config.label, "controller", "controller", "1"))
          .flatTap(r => F.delay(r.start()))
      )(r => F.delay(r.stop()))

      _ <- ControllerAccessories[F, G](root, switchEvents, commands, blocker, fkFuture, fk)
    } yield ())
      .use(
        _ => exitSignal.discrete.map(if (_) None else Some(ExitCode.Success)).unNoneTerminate.compile.toList.map(_.head)
      )

  private def streamLoop[F[_]: Concurrent: Timer: ContextShift: Trace, G[_]: Timer: Concurrent](
    host: String,
    config: Config,
    configFile: ConfigFileSource[F],
    switchEvents: EventSubscriber[F, SwitchEvent],
    commands: EventPublisher[F, CommandEvent],
    blocker: Blocker,
    fkFuture: F ~> Future,
    fk: F ~> Id,
    exitSignal: Signal[F, Boolean],
    logger: Logger[F]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Stream[F, ExitCode] =
    Stream
      .eval(create[F, G](host, config, configFile, switchEvents, commands, blocker, fkFuture, fk, exitSignal))
      .handleErrorWith { th =>
        Stream.eval(logger.error(th)("Homekit failed")) >> Stream
          .sleep[F](10.seconds) >> streamLoop[F, G](
          host,
          config,
          configFile,
          switchEvents,
          commands,
          blocker,
          fkFuture,
          fk,
          exitSignal,
          logger
        )
      }

  private def makeBlocker[F[_]](implicit F: Sync[F]) =
    Blocker.fromExecutorService(F.delay(Executors.newCachedThreadPool(new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = new Thread(r, s"homekit-blocker")
        t.setDaemon(true)
        t
      }
    })))

  def stream[F[_]: Concurrent: Timer: ContextShift: Trace, G[_]: Concurrent: Timer](
    host: String,
    config: Config,
    configFile: ConfigFileSource[F],
    switchEvents: EventSubscriber[F, SwitchEvent],
    commands: EventPublisher[F, CommandEvent],
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Reader[(F ~> Future, F ~> Id, Signal[F, Boolean]), Stream[F, ExitCode]] =
    Reader {
      case (fkFuture, fk, exitSignal) =>
        for {
          blocker <- Stream.resource(makeBlocker[F])
          logger <- Stream.eval(Slf4jLogger.create[F])
          _ <- Stream.eval(logger.info("Starting homekit"))
          exitCode <- streamLoop[F, G](
            host,
            config,
            configFile,
            switchEvents,
            commands,
            blocker,
            fkFuture,
            fk,
            exitSignal,
            logger
          )
        } yield exitCode
    }
}
