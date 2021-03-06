package io.janstenpickle.controller.remotecontrol.git

import java.nio.file.{Files, Path, Paths}
import cats.derived.auto.eq._
import cats.Parallel
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.all._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import io.janstenpickle.controller.model.{CommandPayload, RemoteCommandKey}
import org.apache.commons.io.FileUtils
import eu.timepit.refined.cats._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.PosInt
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue.LongValue

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

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
    onUpdate: (Map[Key, CommandPayload], Map[Key, CommandPayload]) => F[Unit],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit F: Sync[F], provide: Provide[G, F, Span[G]]): Resource[F, LocalCache[F]] = {
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
        Trace[F].span("local.git.cache.load") {
          for {
            _ <- Trace[F]
              .putAll(
                "repo.name" -> repo.name.value,
                "repo.owner" -> repo.owner.value,
                "remote.device" -> key.device.value,
                "remote.name" -> key.name.value
              )
            files <- Trace[F].span("list.path") { F.delay(listPath(commandPath(repo, key)).toList) }
            l <- files
              .traverse { p =>
                F.delay(p -> p.toFile.getName.toLong)
              }
              .map(_.sortBy(_._2))
            p <- l.headOption.traverse {
              case (path, _) =>
                F.delay(new String(Files.readAllBytes(path)).replaceAll("\\s+", "").replaceAll("\n", ""))
            }
          } yield p.map(CommandPayload(_))
        }

      override def store(repo: Repo, updated: Long, key: RemoteCommandKey, payload: CommandPayload): F[Unit] =
        Trace[F].span("local.git.cache.store") {
          for {
            path <- F.delay(commandPath(repo, key).resolve(updated.toString))
            _ <- Trace[F]
              .putAll(
                "updated" -> LongValue(updated),
                "repo.name" -> repo.name.value,
                "repo.owner" -> repo.owner.value,
                "remote.device" -> key.device.value,
                "remote.name" -> key.name.value
              )
            _ <- F.delay(FileUtils.forceMkdirParent(path.toFile))
            _ <- F.delay(Files.write(path, payload.hexValue.getBytes))
          } yield ()
        }

      override def list: F[Map[Key, CommandPayload]] = Trace[F].span("local.git.cache.list") {
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
          data <- repoKeys.flatTraverse {
            case (repo, key) =>
              load(repo, key).map(_.map((repo, key) -> _).toList)
          }
        } yield data.toMap
      }

    }

    Resource.liftF(Slf4jLogger.fromName[F](s"localGitCachePoller")).flatMap { implicit logger =>
      DataPoller.traced[F, G, Map[Key, CommandPayload], LocalCache[F]]("local.git.cache")(
        _ => underlying.list,
        pollInterval,
        errorThreshold,
        onUpdate,
        k
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
}
