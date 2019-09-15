package io.janstenpickle.controller.store.file

import java.nio.file.{Files, Path, Paths}

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.concurrent.Semaphore
import cats.effect.{Blocker, Concurrent, ContextShift, Timer}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe._
import extruder.refined._
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.store.MacroStore
import io.circe.parser
import org.apache.commons.io.{FileUtils, FilenameUtils}
import cats.syntax.parallel._
import cats.instances.list._
import eu.timepit.refined._
import eu.timepit.refined.collection.NonEmpty
import cats.syntax.either._
import io.janstenpickle.controller.store.trace.TracedMacroStore
import natchez.Trace
import natchez.TraceValue.NumberValue

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object FileMacroStore {
  case class Config(location: Path, timeout: FiniteDuration = 1.second)

  def apply[F[_]: ContextShift: Timer: Parallel: Trace](config: Config, blocker: Blocker)(
    implicit F: Concurrent[F]
  ): F[MacroStore[F]] =
    Semaphore[F](1).map { semaphore =>
      TracedMacroStore(
        new MacroStore[F] {
          def evalMutex[A](fa: F[A]): F[A] =
            blocker.blockOn(for {
              _ <- Concurrent.timeout(semaphore.acquire, config.timeout)
              result <- fa
              _ <- semaphore.release
            } yield result)

          private def makePath(name: NonEmptyString) = Paths.get(config.location.toString, s"${name.value}.json")

          override def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] = {
            lazy val file = makePath(name)

            evalMutex(for {
              _ <- F.delay(FileUtils.forceMkdirParent(file.toFile))
              m <- encodeF[F](commands).map(_.spaces2)
              _ <- F.delay(Files.write(file, m.getBytes))
            } yield ())
          }

          override def loadMacro(name: NonEmptyString): F[Option[NonEmptyList[Command]]] = {
            lazy val file = makePath(name)

            lazy val load: F[Option[NonEmptyList[Command]]] = for {
              data <- F.delay(new String(Files.readAllBytes(file)))
              json <- F.fromEither(parser.parse(data))
              m <- decodeF[F, NonEmptyList[Command]](json)
            } yield Some(m)

            blocker.blockOn(F.delay(Files.exists(file)).flatMap {
              case true => load
              case false => Option.empty[NonEmptyList[Command]].pure[F]
            })
          }

          override def listMacros: F[List[NonEmptyString]] =
            blocker
              .delay(Option(config.location.toFile.list()).toList.flatten)
              .flatMap(_.parTraverse { m =>
                F.fromEither(
                  refineV[NonEmpty](FilenameUtils.removeExtension(m)).leftMap(new RuntimeException(_) with NoStackTrace)
                )
              })
        },
        fileStoreType,
        "path" -> config.location.toString,
        "timeout" -> NumberValue(config.timeout.toMillis)
      )
    }

}
