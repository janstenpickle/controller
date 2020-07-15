package io.janstenpickle.controller.extruder

import java.nio.file.{Files, Path, Paths}

import cats.{~>, Eq}
import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.instances.boolean._
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import eu.timepit.refined.types.numeric.PosInt
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.{parser, Json}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.extruder.ConfigFileSource.ConfigFiles
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import io.janstenpickle.trace4cats.inject.Trace
import org.apache.commons.io.FileUtils

import scala.concurrent.duration.FiniteDuration

trait ConfigFileSource[F[_]] { outer =>
  def configs: F[ConfigFiles]
  def write(typesafe: Config): F[Unit]
  def write(json: Json): F[Unit]
  def mapK[G[_]](fk: F ~> G): ConfigFileSource[G] = new ConfigFileSource[G] {
    override def configs: G[ConfigFiles] = fk(outer.configs)
    override def write(typesafe: Config): G[Unit] = fk(outer.write(typesafe))
    override def write(json: Json): G[Unit] = fk(outer.write(json))
  }
}

object ConfigFileSource {
  case class ConfigFiles(typesafe: Config, json: Json, error: Option[Throwable] = None)

  private final val renderOpts: ConfigRenderOptions =
    ConfigRenderOptions.defaults().setJson(true).setComments(false).setOriginComments(false)
  private final val typesafeExtension = "conf"
  private final val jsonExtension = "json"

  implicit val configFilesEq: Eq[ConfigFiles] = Eq.by(_ => true)

  def apply[F[_]: ContextShift: Timer](configFile: Path, blocker: Blocker, timeout: FiniteDuration)(
    implicit F: Concurrent[F],
    trace: Trace[F]
  ): F[ConfigFileSource[F]] =
    Semaphore[F](1).map { semaphore =>
      new ConfigFileSource[F] {
        private def evalMutex[A](fa: F[A]): F[A] =
          blocker.blockOn(
            Resource.make(Concurrent.timeout(semaphore.acquire, timeout))(_ => semaphore.release).use(_ => fa)
          )

        def getFile(extension: String): Path = Paths.get(s"${configFile.toString}.$extension")

        def loadFile(extension: String): F[Option[String]] = trace.span("load.config.file") {
          val file = getFile(extension)
          trace.put("file.name", file.toString) *> blocker.blockOn(for {
            exists <- F.delay(Files.exists(file))
            _ <- trace.put("file.exists", exists)
            ret <- if (exists) trace.span("readFile") {
              trace.put("file.name", file.toString) *> F.delay(new String(Files.readAllBytes(file))).map(Some(_))
            } else F.pure(None)

          } yield ret)
        }

        def writeFile(extension: String)(contents: Array[Byte]): F[Unit] = trace.span("write.config.file") {
          val file = getFile(extension)
          evalMutex(
            trace.put("file.name", file.toString) *> blocker
              .blockOn(F.delay(FileUtils.forceMkdirParent(file.toFile)) *> F.delay(Files.write(file, contents)).void)
          )
        }

        def tConfig: F[Config] = loadFile(typesafeExtension).flatMap {
          case None => F.delay(ConfigFactory.load())
          case Some(conf) => F.delay(ConfigFactory.parseString(conf))
        }

        def json: F[Json] = loadFile(jsonExtension).flatMap {
          case None => Json.Null.pure
          case Some(json) => F.fromEither(parser.parse(json))
        }

        override def configs: F[ConfigFiles] =
          for {
            c <- tConfig
            j <- json
          } yield ConfigFiles(c, j)

        override def write(typesafe: Config): F[Unit] =
          F.delay(typesafe.root().render(renderOpts).getBytes).flatMap(writeFile(typesafeExtension))

        override def write(json: Json): F[Unit] =
          F.delay(json.spaces2.getBytes).flatMap(writeFile(jsonExtension))
      }
    }

  implicit val configEmpty: Empty[ConfigFiles] = Empty(ConfigFiles(ConfigFactory.empty(), Json.Null))

  def polling[F[_]: ContextShift: Timer: Trace, G[_]: Concurrent: Timer](
    configFile: Path,
    pollInterval: FiniteDuration,
    blocker: Blocker,
    timeout: FiniteDuration
  )(implicit F: Concurrent[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, ConfigFileSource[F]] =
    Resource.liftF(Slf4jLogger.fromName[F](s"configFilePoller-${configFile.toString}")).flatMap { implicit logger =>
      Resource.liftF(apply[F](configFile, blocker, timeout)).flatMap { source =>
        DataPoller
          .traced[F, G, ConfigFiles, ConfigFileSource[F]]("config.file.source", "config.file" -> configFile.toString)(
            (_: Data[ConfigFiles]) => source.configs,
            pollInterval,
            PosInt(1),
            (data: Data[ConfigFiles], th: Throwable) => F.pure(data.value.copy(error = Some(th))),
            (_: ConfigFiles, _: ConfigFiles) => F.unit
          ) { (get, set) =>
            new ConfigFileSource[F] {
              override def configs: F[ConfigFiles] = get()

              override def write(typesafe: Config): F[Unit] = get().flatMap { files =>
                source.write(typesafe) *> set(files.copy(typesafe = typesafe))
              }

              override def write(json: Json): F[Unit] = get().flatMap { files =>
                source.write(json) *> set(files.copy(json = json))
              }
            }
          }
      }
    }
}
