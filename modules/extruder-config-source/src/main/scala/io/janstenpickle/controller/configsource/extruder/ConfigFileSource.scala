package io.janstenpickle.controller.configsource.extruder

import java.nio.file.{Files, Path, Paths}

import cats.Eq
import cats.effect._
import cats.instances.boolean._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.typesafe.config.{Config, ConfigFactory}
import eu.timepit.refined.types.numeric.PosInt
import io.circe.{parser, Json}
import io.janstenpickle.catseffect.CatsEffect.{evalOn, suspendErrors}
import io.janstenpickle.controller.configsource.extruder.ConfigFileSource.ConfigFiles
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.poller.{DataPoller, Empty}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

trait ConfigFileSource[F[_]] {
  def configs: F[ConfigFiles]
}

object ConfigFileSource {
  case class ConfigFiles(typesafe: Config, yaml: String, json: Json, error: Option[Throwable] = None)

  implicit val configFilesEq: Eq[ConfigFiles] = Eq.by(_ => true)

  def apply[F[_]: ContextShift](configFile: Path, ec: ExecutionContext)(implicit F: Sync[F]): ConfigFileSource[F] =
    new ConfigFileSource[F] {
      def loadFile(extension: String): F[Option[String]] = {
        val file = Paths.get(s"${configFile.toString}.$extension")
        evalOn(for {
          exists <- suspendErrors(Files.exists(file))
          ret <- if (exists) suspendErrors(new String(Files.readAllBytes(file))).map(Some(_))
          else Option.empty[String].pure

        } yield ret, ec)
      }

      def tConfig: F[Config] = loadFile("conf").flatMap {
        case None => ConfigFactory.empty().pure
        case Some(conf) => suspendErrors(ConfigFactory.parseString(conf))
      }

      def yaml: F[String] = loadFile("yaml").map {
        case None => "---"
        case Some(yaml) => yaml
      }

      def json: F[Json] = loadFile("json").flatMap {
        case None => Json.Null.pure
        case Some(json) => F.fromEither(parser.parse(json))
      }

      override def configs: F[ConfigFiles] =
        for {
          c <- tConfig
          y <- yaml
          j <- json
        } yield ConfigFiles(c, y, j)
    }

  implicit val configEmpty: Empty[ConfigFiles] = Empty(ConfigFiles(ConfigFactory.empty(), "---", Json.Null))

  def polling[F[_]: ContextShift: Timer](configFile: Path, pollInterval: FiniteDuration, ec: ExecutionContext)(
    implicit F: Concurrent[F]
  ): Resource[F, ConfigFileSource[F]] = {
    val source = apply[F](configFile, ec)

    DataPoller[F, ConfigFiles, ConfigFileSource[F]](
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
