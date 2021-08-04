package io.janstenpickle.controller.extruder

import cats.effect._
import cats.effect.std.Semaphore
import cats.instances.boolean._
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Applicative, ApplicativeError, Eq}
import eu.timepit.refined.types.numeric.PosInt
import io.circe.{parser, Json}
import io.janstenpickle.controller.extruder.ConfigFileSource.ConfigFiles
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import org.apache.commons.io.FileUtils
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.FiniteDuration

trait ConfigFileSource[F[_]] { outer =>
  def configs: F[ConfigFiles]
  def write(json: Json): F[Unit]
  def mapK[G[_]](fk: F ~> G): ConfigFileSource[G] = new ConfigFileSource[G] {
    override def configs: G[ConfigFiles] = fk(outer.configs)
    override def write(json: Json): G[Unit] = fk(outer.write(json))
  }
}

object ConfigFileSource {
  case class ConfigFiles(json: Json, error: Option[Throwable] = None)

  private final val typesafeExtension = "conf"
  private final val jsonExtension = "json"

  implicit val configFilesEq: Eq[ConfigFiles] = Eq.by(_ => true)

  def apply[F[_]: Async](configFile: Path, timeout: FiniteDuration)(implicit trace: Trace[F]): F[ConfigFileSource[F]] =
    Semaphore[F](1).map { semaphore =>
      new ConfigFileSource[F] {
        private def evalMutex[A](fa: F[A]): F[A] =
          Resource
            .makeFull { (poll: Poll[F]) =>
              poll(Concurrent[F].timeout(semaphore.acquire, timeout))
            }(_ => semaphore.release)
            .use(_ => fa)

        def getFile(extension: String): Path = Paths.get(s"${configFile.toString}.$extension")

        def loadFile(extension: String): F[Option[String]] = trace.span("load.config.file") {
          val file = getFile(extension)
          for {
            _ <- trace.put("file.name", file.toString)
            exists <- Sync[F].blocking(Files.exists(file))
            _ <- trace.put("file.exists", exists)
            ret <- if (exists) trace.span("readFile") {
              trace
                .put("file.name", file.toString) *> Sync[F].blocking(new String(Files.readAllBytes(file))).map(Some(_))
            } else Applicative[F].pure(None)

          } yield ret
        }

        def writeFile(extension: String)(contents: Array[Byte]): F[Unit] = trace.span("write.config.file") {
          val file = getFile(extension)
          evalMutex(
            trace.put("file.name", file.toString) *> Sync[F]
              .blocking(FileUtils.forceMkdirParent(file.toFile)) *> Sync[F].blocking(Files.write(file, contents)).void
          )
        }

        def json: F[Json] = loadFile(jsonExtension).flatMap {
          case None => Json.Null.pure
          case Some(json) => ApplicativeError[F, Throwable].fromEither(parser.parse(json))
        }

        override def configs: F[ConfigFiles] =
          json.map(ConfigFiles(_))

        override def write(json: Json): F[Unit] =
          Sync[F].delay(json.spaces2.getBytes).flatMap(writeFile(jsonExtension))
      }
    }

  implicit val configEmpty: Empty[ConfigFiles] = Empty(ConfigFiles(Json.Null))

  def polling[F[_]: Async: Trace, G[_]: Async](
    configFile: Path,
    pollInterval: FiniteDuration,
    timeout: FiniteDuration,
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, ConfigFileSource[F]] =
    Resource.eval(Slf4jLogger.fromName[F](s"configFilePoller-${configFile.toString}")).flatMap { implicit logger =>
      Resource.eval(apply[F](configFile, timeout)).flatMap { source =>
        DataPoller
          .traced[F, G, ConfigFiles, ConfigFileSource[F]]("config.file.source", "config.file" -> configFile.toString)(
            (_: Data[ConfigFiles]) => source.configs,
            pollInterval,
            PosInt(1),
            (data: Data[ConfigFiles], th: Throwable) => Applicative[F].pure(data.value.copy(error = Some(th))),
            (_: ConfigFiles, _: ConfigFiles) => Applicative[F].unit,
            k
          ) { (get, set) =>
            new ConfigFileSource[F] {
              override def configs: F[ConfigFiles] = get()

              override def write(json: Json): F[Unit] = get().flatMap { files =>
                source.write(json) *> set(files.copy(json = json))
              }
            }
          }
      }
    }
}
