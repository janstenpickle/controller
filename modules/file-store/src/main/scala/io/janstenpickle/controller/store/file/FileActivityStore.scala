package io.janstenpickle.controller.store.file

import java.nio.file.{Files, Path, Paths}

import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.store.ActivityStore
import org.apache.commons.io.FileUtils

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object FileActivityStore {
  case class Config(location: Path, timeout: FiniteDuration = 1.second)

  def apply[F[_]: Concurrent: ContextShift: Timer](config: Config): Resource[F, ActivityStore[F]] =
    cachedExecutorResource.evalMap(apply(config, _))

  def apply[F[_]: Concurrent: ContextShift: Timer](config: Config, ec: ExecutionContext): F[ActivityStore[F]] = {
    def eval[A](fa: F[A]): F[A] = evalOn(fa, ec)

    Semaphore[F](1).map { semaphore =>
      new ActivityStore[F] {
        private def evalMutex[A](fa: F[A]): F[A] =
          eval(for {
            _ <- Concurrent.timeout(semaphore.acquire, config.timeout)
            result <- fa
            _ <- semaphore.release
          } yield result)

        private def makePath(room: Room) = Paths.get(config.location.toString, room.value)

        override def storeActivity(room: Room, name: NonEmptyString): F[Unit] = {
          lazy val file = makePath(room)

          evalMutex(for {
            _ <- suspendErrors(FileUtils.forceMkdirParent(file.toFile))
            _ <- suspendErrors(Files.write(file, name.value.getBytes))
          } yield ())
        }

        override def loadActivity(room: Room): F[Option[NonEmptyString]] = {
          lazy val file = makePath(room)

          lazy val load: F[Option[NonEmptyString]] = for {
            data <- suspendErrors(Files.readAllBytes(file))
            string <- suspendErrors(new String(data))
          } yield refineV[NonEmpty](string).toOption

          eval(suspendErrors(Files.exists(file)).flatMap {
            case true => load
            case false => Option.empty[NonEmptyString].pure
          })
        }
      }
    }
  }
}
