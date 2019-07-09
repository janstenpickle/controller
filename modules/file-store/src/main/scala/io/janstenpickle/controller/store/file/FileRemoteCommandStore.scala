package io.janstenpickle.controller.store.file

import java.nio.file.{Files, Path, Paths}

import cats.Parallel
import cats.effect.concurrent.Semaphore
import cats.effect.{Blocker, Concurrent, ContextShift, Sync, Timer}
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import eu.timepit.refined._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.store.trace.TracedRemoteCommandStore
import io.janstenpickle.controller.store.{RemoteCommand, RemoteCommandStore}
import natchez.Trace
import natchez.TraceValue.NumberValue
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._

object FileRemoteCommandStore {
  case class Config(location: Path, timeout: FiniteDuration = 1.second)

  def apply[F[_]: ContextShift: Timer: Parallel: Trace, T](
    config: Config,
    blocker: Blocker
  )(implicit F: Concurrent[F], cs: CommandSerde[F, T]): F[RemoteCommandStore[F, T]] =
    Semaphore[F](1).map { semaphore =>
      TracedRemoteCommandStore(
        new RemoteCommandStore[F, T] {
          def evalMutex[A](fa: F[A]): F[A] =
            blocker.blockOn(for {
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
              _ <- Sync[F].delay(FileUtils.forceMkdirParent(file.toFile))
              data <- cs.serialize(payload)
              _ <- Sync[F].delay(Files.write(file, data))
            } yield ())
          }

          override def loadCommand(
            remote: NonEmptyString,
            device: NonEmptyString,
            name: NonEmptyString
          ): F[Option[T]] = {
            lazy val file = Paths.get(config.location.toString, remote.value, device.value, name.value)

            lazy val load: F[Option[T]] =
              for {
                data <- Sync[F].delay(Files.readAllBytes(file))
                payload <- cs.deserialize(data)
              } yield Some(payload)

            blocker.blockOn(Sync[F].delay(Files.exists(file)).flatMap {
              case true => load
              case false => Option.empty[T].pure[F]
            })
          }

          override def listCommands: F[List[RemoteCommand]] =
            blocker
              .delay(for {
                remote <- Option(config.location.toFile.listFiles()).toList.flatten
                device <- Option(remote.listFiles()).toList.flatten
                name <- Option(device.listFiles()).toList.flatten
              } yield (remote, device, name))
              .flatMap(_.parTraverse {
                case (remote, device, name) =>
                  F.fromEither((for {
                    r <- refineV[NonEmpty](remote.getName)
                    d <- refineV[NonEmpty](device.getName)
                    n <- refineV[NonEmpty](name.getName)
                  } yield RemoteCommand(r, d, n)).leftMap(new RuntimeException(_)))
              })
        },
        fileStoreType,
        "path" -> config.location.toString,
        "timeout" -> NumberValue(config.timeout.toMillis)
      )
    }

}
