package io.janstenpickle.controller.store.file

import java.nio.file.{Files, Path, Paths}

import cats.data.NonEmptyList
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe.yaml._
import extruder.refined._
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.store.MacroStore
import org.apache.commons.io.FileUtils

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object FileMacroStore {
  case class Config(location: Path, timeout: FiniteDuration = 1.second)

  def apply[F[_]: Concurrent: ContextShift: Timer](config: Config): Resource[F, MacroStore[F]] =
    cachedExecutorResource.evalMap(apply(config, _))

  def apply[F[_]: Concurrent: ContextShift: Timer](config: Config, ec: ExecutionContext): F[MacroStore[F]] = {
    def eval[A](fa: F[A]): F[A] = evalOn(fa, ec)

    Semaphore[F](1).map { semaphore =>
      new MacroStore[F] {
        def evalMutex[A](fa: F[A]): F[A] =
          eval(for {
            _ <- Concurrent.timeout(semaphore.acquire, config.timeout)
            result <- fa
            _ <- semaphore.release
          } yield result)

        override def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] = {
          lazy val file = Paths.get(config.location.toString, name.value)

          evalMutex(for {
            _ <- suspendErrors(FileUtils.forceMkdirParent(file.toFile))
            m <- encodeF[F](commands)
            _ <- suspendErrors(Files.write(file, m.getBytes))
          } yield ())
        }

        override def loadMacro(name: NonEmptyString): F[Option[NonEmptyList[Command]]] = {
          lazy val file = Paths.get(config.location.toString, name.value)

          lazy val load: F[Option[NonEmptyList[Command]]] = for {
            data <- suspendErrors(new String(Files.readAllBytes(file)))
            m <- decodeF[F, NonEmptyList[Command]](data)
          } yield Some(m)

          eval(suspendErrors(Files.exists(file)).flatMap {
            case true => load
            case false => Option.empty[NonEmptyList[Command]].pure
          })
        }
      }
    }

  }
}
