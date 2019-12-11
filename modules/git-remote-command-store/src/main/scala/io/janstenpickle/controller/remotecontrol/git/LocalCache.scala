package io.janstenpickle.controller.remotecontrol.git

import java.nio.file.{Files, Path, Paths}

import cats.derived.auto.eq._
import cats.Parallel
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.all._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.traverse._
import io.janstenpickle.controller.model.{CommandPayload, RemoteCommandKey}
import org.apache.commons.io.FileUtils
import eu.timepit.refined.cats._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.PosInt
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.poller.DataPoller
import natchez.Trace

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

trait LocalCache[F[_]] {
  def load(repo: Repo, key: RemoteCommandKey): F[Option[CommandPayload]]
  def store(repo: Repo, updated: Long, key: RemoteCommandKey, payload: CommandPayload): F[Unit]
  def list: F[Map[(Repo, RemoteCommandKey), CommandPayload]]
}

object LocalCache {
  type Key = (Repo, RemoteCommandKey)

  private val master = "master"

  def apply[F[_]: Parallel: Trace, G[_]: Concurrent: Timer](
    path: Path,
    pollInterval: FiniteDuration,
    errorThreshold: PosInt,
    onUpdate: Map[Key, CommandPayload] => F[Unit]
  )(implicit F: Sync[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, LocalCache[F]] = {
    implicit val valueOrder: Ordering[Long] = Ordering.Long.reverse

    val underlying: LocalCache[F] = new LocalCache[F] {
      private def commandPath(repo: Repo, key: RemoteCommandKey) =
        path.resolve(
          Paths.get(
            repo.name.value,
            repo.owner.value,
            repo.repo.value,
            repo.ref.fold(master)(_.value),
            key.device.value,
            key.name.value
          )
        )

      private def listPath(p: Path): Iterator[Path] =
        if (Files.exists(p)) Files.list(p).iterator().asScala
        else Iterator.empty

      override def load(repo: Repo, key: RemoteCommandKey): F[Option[CommandPayload]] =
        for {
          files <- F.delay(listPath(commandPath(repo, key)).toList)
          l <- files
            .parTraverse { p =>
              F.delay(p -> p.toFile.getName.toLong)
            }
            .map(_.sortBy(_._2))
          p <- l.headOption.traverse {
            case (path, _) =>
              F.delay(new String(Files.readAllBytes(path)).replaceAll("\\s+", "").replaceAllLiterally("\n", ""))
          }
        } yield p.map(CommandPayload(_))

      override def store(repo: Repo, updated: Long, key: RemoteCommandKey, payload: CommandPayload): F[Unit] =
        for {
          path <- F.delay(commandPath(repo, key).resolve(updated.toString))
          _ <- F.delay(FileUtils.forceMkdirParent(path.toFile))
          _ <- F.delay(Files.write(path, payload.hexValue.getBytes))
        } yield ()

      override def list: F[Map[Key, CommandPayload]] =
        for {
          repoKeys <- F.delay((for {
            repoName <- listPath(path)
            owner <- listPath(repoName)
            repo <- listPath(owner)
            ref <- listPath(repo)
            device <- listPath(ref)
            name <- listPath(device)
            rn <- refineV[NonEmpty](repoName.toFile.getName).toOption
            u <- refineV[NonEmpty](owner.toFile.getName).toOption
            r <- refineV[NonEmpty](repo.toFile.getName).toOption
            rf <- refineV[NonEmpty](ref.toFile.getName).toOption
            d <- refineV[NonEmpty](device.toFile.getName).toOption
            n <- refineV[NonEmpty](name.toFile.getName).toOption
          } yield {
            val repo = Repo(rn, u, r, Some(rf).filterNot(_.value == master))
            repo -> RemoteCommandKey(commandSource(repo), d, n)
          }).toList)
          data <- repoKeys.parFlatTraverse {
            case (repo, key) =>
              load(repo, key).map(_.map((repo, key) -> _).toList)
          }
        } yield data.toMap

    }

    DataPoller.traced[F, G, Map[Key, CommandPayload], LocalCache[F]]("local.git.cache")(
      _ => underlying.list,
      pollInterval,
      errorThreshold,
      onUpdate
    )(
      (getData, update) =>
        new LocalCache[F] {
          override def load(repo: Repo, key: RemoteCommandKey): F[Option[CommandPayload]] =
            getData().map(_.get(repo -> key))

          override def store(repo: Repo, updated: Long, key: RemoteCommandKey, payload: CommandPayload): F[Unit] =
            underlying.store(repo, updated, key, payload) *> getData().flatMap { current =>
              update(current.updated((repo, key), payload))
            }

          override def list: F[Map[(Repo, RemoteCommandKey), CommandPayload]] = getData()
      }
    )
  }
}
