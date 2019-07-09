package io.janstenpickle.controller.store.file

import java.nio.file.{Files, Path, Paths}

import cats.effect.concurrent.Semaphore
import cats.effect._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.store.ActivityStore
import io.janstenpickle.controller.store.trace.TracedActivityStore
import natchez.Trace
import natchez.TraceValue.NumberValue
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._

object FileActivityStore {
  case class Config(location: Path, timeout: FiniteDuration = 1.second)

  def apply[F[_]: Concurrent: ContextShift: Timer: Trace](config: Config, blocker: Blocker): F[ActivityStore[F]] =
    Semaphore[F](1).map { semaphore =>
      TracedActivityStore(
        new ActivityStore[F] {
          private def evalMutex[A](fa: F[A]): F[A] =
            blocker.blockOn(for {
              _ <- Concurrent.timeout(semaphore.acquire, config.timeout)
              result <- fa
              _ <- semaphore.release
            } yield result)

          private def makePath(room: Room) = Paths.get(config.location.toString, room.value)

          override def storeActivity(room: Room, name: NonEmptyString): F[Unit] = {
            lazy val file = makePath(room)

            evalMutex(for {
              _ <- Sync[F].delay(FileUtils.forceMkdirParent(file.toFile))
              _ <- Sync[F].delay(Files.write(file, name.value.getBytes))
            } yield ())
          }

          override def loadActivity(room: Room): F[Option[NonEmptyString]] = {
            lazy val file = makePath(room)

            lazy val load: F[Option[NonEmptyString]] = for {
              data <- Sync[F].delay(Files.readAllBytes(file))
              string <- Sync[F].delay(new String(data))
            } yield refineV[NonEmpty](string).toOption

            blocker.blockOn(Sync[F].delay(Files.exists(file)).flatMap {
              case true => load
              case false => Option.empty[NonEmptyString].pure
            })
          }
        },
        fileStoreType,
        "path" -> config.location.toString,
        "timeout" -> NumberValue(config.timeout.toMillis)
      )
    }
}
