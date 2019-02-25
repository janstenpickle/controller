package io.janstenpickle.controller.store.file

import java.nio.file.{Files, Path, Paths}

import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.store.RemoteCommandStore
import org.apache.commons.io.FileUtils

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object FileRemoteCommandStore {
  case class Config(location: Path, timeout: FiniteDuration = 1.second)

  def apply[F[_]: Concurrent: ContextShift: Timer, T](
    config: Config
  )(implicit cs: CommandSerde[F, T]): Resource[F, RemoteCommandStore[F, T]] =
    cachedExecutorResource.evalMap(apply(config, _))

  def apply[F[_]: Concurrent: ContextShift: Timer, T](config: Config, ec: ExecutionContext)(
    implicit cs: CommandSerde[F, T]
  ): F[RemoteCommandStore[F, T]] = {
    def eval[A](fa: F[A]): F[A] = evalOn(fa, ec)

    Semaphore[F](1).map { semaphore =>
      new RemoteCommandStore[F, T] {
        def evalMutex[A](fa: F[A]): F[A] =
          eval(for {
            _ <- Concurrent.timeout(semaphore.acquire, config.timeout)
            result <- fa
            _ <- semaphore.release
          } yield result)

        override def storeCode(
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

        override def loadCode(
          remote: NonEmptyString,
          device: NonEmptyString,
          name: NonEmptyString
        ): F[Option[T]] = {
          lazy val file = Paths.get(config.location.toString, remote.value, device.value, name.value)

          lazy val load: F[Option[T]] =
            for {
              data <- suspendErrors(Files.readAllBytes(file))
              payload <- cs.deserialize(data)
            } yield Some(payload)

          eval(suspendErrors(Files.exists(file)).flatMap {
            case true => load
            case false => Option.empty[T].pure
          })
        }
      }
    }
  }

}
