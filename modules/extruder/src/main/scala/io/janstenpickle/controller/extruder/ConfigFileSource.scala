package io.janstenpickle.controller.extruder

import java.nio.file.{Files, Path, Paths}

import cats.Eq
import cats.effect._
import cats.instances.boolean._
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.typesafe.config.{Config, ConfigFactory}
import eu.timepit.refined.types.numeric.PosInt
import io.circe.{parser, Json}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.extruder.ConfigFileSource.ConfigFiles
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import natchez.Trace

import scala.concurrent.duration.FiniteDuration

trait ConfigFileSource[F[_]] {
  def configs: F[ConfigFiles]
}

object ConfigFileSource {
  case class ConfigFiles(typesafe: Config, json: Json, error: Option[Throwable] = None)

  implicit val configFilesEq: Eq[ConfigFiles] = Eq.by(_ => true)

  def apply[F[_]: ContextShift](
    configFile: Path,
    blocker: Blocker
  )(implicit F: Sync[F], trace: Trace[F]): ConfigFileSource[F] =
    new ConfigFileSource[F] {
      def loadFile(extension: String): F[Option[String]] = trace.span("loadFile") {
        val file = Paths.get(s"${configFile.toString}.$extension")
        trace.put("file.name" -> file.toString) *> blocker.blockOn(for {
          exists <- F.delay(Files.exists(file))
          _ <- trace.put("file.exists" -> exists)
          ret <- if (exists) trace.span("readFile") {
            trace.put("file.name" -> file.toString) *> F.delay(new String(Files.readAllBytes(file))).map(Some(_))
          } else F.pure(None)

        } yield ret)
      }

      def tConfig: F[Config] = loadFile("conf").flatMap {
        case None => F.delay(ConfigFactory.load())
        case Some(conf) => F.delay(ConfigFactory.parseString(conf))
      }

      def json: F[Json] = loadFile("json").flatMap {
        case None => Json.Null.pure
        case Some(json) => F.fromEither(parser.parse(json))
      }

      override def configs: F[ConfigFiles] =
        for {
          c <- tConfig
          j <- json
        } yield ConfigFiles(c, j)
    }

  implicit val configEmpty: Empty[ConfigFiles] = Empty(ConfigFiles(ConfigFactory.empty(), Json.Null))

  def polling[F[_]: ContextShift: Trace, G[_]: Concurrent: Timer](
    configFile: Path,
    pollInterval: FiniteDuration,
    blocker: Blocker
  )(implicit F: Sync[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, ConfigFileSource[F]] = {
    val source = apply[F](configFile, blocker)

    DataPoller.traced[F, G, ConfigFiles, ConfigFileSource[F]]("configFileSource")(
      (_: Data[ConfigFiles]) => source.configs,
      pollInterval,
      PosInt(1),
      (data: Data[ConfigFiles], th: Throwable) => F.pure(data.value.copy(error = Some(th))),
      (_: ConfigFiles) => F.unit
    ) { (get, _) =>
      new ConfigFileSource[F] {
        override def configs: F[ConfigFiles] = get()
      }
    }
  }
}
