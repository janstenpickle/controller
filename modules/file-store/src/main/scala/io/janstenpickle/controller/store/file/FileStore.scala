package io.janstenpickle.controller.store.file

import java.nio.file.{Files, Path, Paths}

import cats.data.NonEmptyList
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.refineV
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe.yaml._
import extruder.refined._
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.model.{Command, CommandPayload}
import io.janstenpickle.controller.store.Store
import javax.xml.bind.DatatypeConverter
import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.FileUtils

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object FileStore {
  case class Config(codeLocation: Path, activityLocation: Path, macroLocation: Path, timeout: FiniteDuration = 1.second)

  def apply[F[_]: Concurrent: ContextShift: Timer](config: Config): Resource[F, Store[F]] =
    cachedExecutorResource.evalMap(apply(config, _))

  def apply[F[_]: Concurrent: ContextShift: Timer](config: Config, ec: ExecutionContext): F[Store[F]] = {
    def eval[A](fa: F[A]): F[A] = evalOn(fa, ec)

    def evalMutex[A](fa: F[A], semaphore: Semaphore[F]): F[A] =
      eval(for {
        _ <- Concurrent.timeout(semaphore.acquire, config.timeout)
        result <- fa
        _ <- semaphore.release
      } yield result)

    for {
      macroSemaphore <- Semaphore[F](1)
      codeSemaphore <- Semaphore[F](1)
      activitySemaphore <- Semaphore[F](1)
    } yield
      new Store[F] {
        override def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] = {
          lazy val file = Paths.get(config.macroLocation.toString, name.value)

          evalMutex(for {
            _ <- suspendErrors(FileUtils.forceMkdirParent(file.toFile))
            m <- encodeF[F](commands)
            _ <- suspendErrors(Files.write(file, m.getBytes))
          } yield (), macroSemaphore)
        }

        override def loadMacro(name: NonEmptyString): F[Option[NonEmptyList[Command]]] = {
          lazy val file = Paths.get(config.macroLocation.toString, name.value)

          lazy val load: F[Option[NonEmptyList[Command]]] = for {
            data <- suspendErrors(new String(Files.readAllBytes(file)))
            m <- decodeF[F, NonEmptyList[Command]](data)
          } yield Some(m)

          eval(suspendErrors(Files.exists(file)).flatMap {
            case true => load
            case false => Option.empty[NonEmptyList[Command]].pure
          })
        }

        override def storeCode(
          remote: NonEmptyString,
          device: NonEmptyString,
          name: NonEmptyString,
          payload: CommandPayload
        ): F[Unit] = {
          lazy val file = Paths.get(config.codeLocation.toString, remote.value, device.value, name.value)

          evalMutex(for {
            _ <- suspendErrors(FileUtils.forceMkdirParent(file.toFile))
            _ <- suspendErrors(Files.write(file, payload.hexValue.getBytes))
          } yield (), codeSemaphore)
        }

        override def loadCode(
          remote: NonEmptyString,
          device: NonEmptyString,
          name: NonEmptyString
        ): F[Option[CommandPayload]] = {
          lazy val file = Paths.get(config.codeLocation.toString, remote.value, device.value, name.value)

          lazy val load: F[Option[CommandPayload]] = suspendErrors(
            Some(CommandPayload(new String(Files.readAllBytes(file))))
          )

          eval(suspendErrors(Files.exists(file)).flatMap {
            case true => load
            case false => Option.empty[CommandPayload].pure
          })
        }

        override def storeActivity(name: NonEmptyString): F[Unit] =
          evalMutex(for {
            _ <- suspendErrors(FileUtils.forceMkdirParent(config.activityLocation.toFile))
            _ <- suspendErrors(Files.write(config.activityLocation, name.value.getBytes))
          } yield (), activitySemaphore)

        override def loadActivity: F[Option[NonEmptyString]] = {
          lazy val load: F[Option[NonEmptyString]] = for {
            data <- suspendErrors(Files.readAllBytes(config.activityLocation))
            string <- suspendErrors(new String(data))
          } yield refineV[NonEmpty](string).toOption

          eval(suspendErrors(Files.exists(config.activityLocation)).flatMap {
            case true => load
            case false => Option.empty[NonEmptyString].pure
          })
        }
      }

  }
}
