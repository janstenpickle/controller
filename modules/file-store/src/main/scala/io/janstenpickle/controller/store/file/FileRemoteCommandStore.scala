package io.janstenpickle.controller.store.file

import java.nio.file.{Files, Path, Paths}

import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import eu.timepit.refined._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.store.{RemoteCommand, RemoteCommandStore}
import org.apache.commons.io.FileUtils

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object FileRemoteCommandStore {
  case class Config(location: Path, timeout: FiniteDuration = 1.second)

  def apply[F[_]: Concurrent: ContextShift: Timer, T](
    config: Config
  )(implicit cs: CommandSerde[F, T]): Resource[F, RemoteCommandStore[F, T]] =
    cachedExecutorResource.evalMap(apply(config, _))

  def apply[F[_]: ContextShift: Timer, T](
    config: Config,
    ec: ExecutionContext
  )(implicit F: Concurrent[F], cs: CommandSerde[F, T]): F[RemoteCommandStore[F, T]] = {
    def eval[A](fa: F[A]): F[A] = evalOn(fa, ec)

    Semaphore[F](1).map { semaphore =>
      new RemoteCommandStore[F, T] {
        def evalMutex[A](fa: F[A]): F[A] =
          eval(for {
            _ <- Concurrent.timeout(semaphore.acquire, config.timeout)
            result <- fa
            _ <- semaphore.release
          } yield result)

        override def storeCommand(
          remote: NonEmptyString,
          device: NonEmptyString,
          name: NonEmptyString,
          payload: T
        ): F[Unit] = {
          lazy val file = Paths.get(config.location.toString, remote.value, device.value, name.value)

          evalMutex(for {
            _ <- suspendErrors(FileUtils.forceMkdirParent(file.toFile))
            data <- cs.serialize(payload)
            _ <- suspendErrors(Files.write(file, data))
          } yield ())
        }

        override def loadCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Option[T]] = {
          lazy val file = Paths.get(config.location.toString, remote.value, device.value, name.value)

          lazy val load: F[Option[T]] =
            for {
              data <- suspendErrors(Files.readAllBytes(file))
              payload <- cs.deserialize(data)
            } yield Some(payload)

          eval(suspendErrors(Files.exists(file)).flatMap {
            case true => load
            case false => Option.empty[T].pure[F]
          })
        }

        override def listCommands: F[List[RemoteCommand]] =
          suspendErrorsEvalOn(
            for {
              remote <- Option(config.location.toFile.listFiles()).toList.flatten
              device <- Option(remote.listFiles()).toList.flatten
              name <- Option(device.listFiles()).toList.flatten
            } yield (remote, device, name),
            ec
          ).flatMap(_.traverse {
            case (remote, device, name) =>
              F.fromEither((for {
                r <- refineV[NonEmpty](remote.getName)
                d <- refineV[NonEmpty](device.getName)
                n <- refineV[NonEmpty](name.getName)
              } yield RemoteCommand(r, d, n)).leftMap(new RuntimeException(_)))
          })
      }
    }
  }

}
