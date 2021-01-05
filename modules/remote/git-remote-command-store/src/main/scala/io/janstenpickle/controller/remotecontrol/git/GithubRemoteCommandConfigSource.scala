package io.janstenpickle.controller.remotecontrol.git

import java.nio.file.{Path, Paths}
import java.time.Instant
import java.util.Base64
import cats.data.{NonEmptyList, OptionT}
import cats.derived.auto.eq._
import cats.effect._
import cats.instances.all._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.traverse._
import cats.{~>, Functor, Parallel}
import eu.timepit.refined.cats._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.{refineMV, refineV}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.Decoder
import io.circe.generic.auto._
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.model.{CommandPayload, RemoteCommandKey}
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.remotecontrol.git.GithubRemoteCommandConfigSource._
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.Http4sDsl
import org.http4s.{Credentials, EntityDecoder, Uri}
import org.http4s.headers.Authorization
import org.http4s.util.CaseInsensitiveString

import scala.concurrent.duration._

class GithubRemoteCommandConfigSource[F[_]: Parallel](
  repos: NonEmptyList[Repo],
  listRemote: () => F[RepoListing],
  gv: (Repo, RemoteCommandKey) => F[Option[CommandPayload]]
)(implicit F: Sync[F], trace: Trace[F])
    extends ConfigSource[F, RemoteCommandKey, CommandPayload] {

  override def functor: Functor[F] = F

  override def getValue(key: RemoteCommandKey): F[Option[CommandPayload]] =
    repos.toList
      .parFlatTraverse { repo =>
        gv(repo, key).map(_.toList)
      }
      .map(_.headOption)

  override def getConfig: F[ConfigResult[RemoteCommandKey, CommandPayload]] =
    listRemote()
      .flatMap(_.parFlatTraverse {
        case (repo, device, name, _) =>
          val key = RemoteCommandKey(commandSource(repo), device, name)
          gv(repo, key).map(_.toList.map(key -> _))
      })
      .map(vs => ConfigResult(vs.toMap))

  override def listKeys: F[Set[RemoteCommandKey]] =
    listRemote().map(
      _.map { case (repo, device, name, _) => RemoteCommandKey(commandSource(repo), device, name) }.toSet
    )
}

object GithubRemoteCommandConfigSource {
  final val DefaultRepo = Repo(refineMV("global"), refineMV("janstenpickle"), refineMV("broadlink-remote-commands"))

  private final val reposUri = "https://api.github.com/repos"

  private implicit def eitherDecoder[A: Decoder, B: Decoder]: Decoder[Either[A, B]] =
    Decoder[A].map(Either.left[A, B](_)).or(Decoder[B].map(Either.right[A, B](_)))

  case class Config(
    repos: List[Repo] = List.empty,
    accessToken: Option[NonEmptyString] = None,
    localCacheDir: Path = Paths.get("/", "tmp", "controller", "cache", "remote-command"),
    gitPollInterval: FiniteDuration = 3.hours,
    cachePollInterval: FiniteDuration = 30.seconds,
    pollErrorThreshold: PosInt = PosInt(Int.MaxValue),
    autoUpdate: Boolean = true
  )

  type RepoListing = List[(Repo, NonEmptyString, NonEmptyString, Content)]

  private final val hexSuffix = ".hex"

  case class Content(`type`: String, name: String, path: String, encoding: Option[String], content: Option[String])
  case class Commit(commit: CommitInfo)
  case class CommitInfo(committer: Committer)
  case class Committer(date: Instant)

  def apply[F[_]: Parallel, G[_]: Concurrent: Timer](
    client: Client[F],
    config: Config,
    onUpdate: (Any, Any) => F[Unit],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(
    implicit F: Sync[F],
    trace: Trace[F],
    provide: Provide[G, F, Span[G]]
  ): Resource[F, GithubRemoteCommandConfigSource[F]] = Resource.liftF(Slf4jLogger.fromClass[F](this.getClass)).flatMap {
    logger =>
      val dsl = new Http4sDsl[F] with Http4sClientDsl[F] {}
      import dsl._

      implicit val contentsDecoder: EntityDecoder[F, Either[Content, NonEmptyList[Content]]] =
        jsonOf[F, Either[Content, NonEmptyList[Content]]]
      implicit val commitDecoder: EntityDecoder[F, List[Commit]] = jsonOf[F, List[Commit]]

      lazy val repos = NonEmptyList(DefaultRepo, config.repos)
      lazy val reversedRepos = repos.reverse

      implicit val epochOrder: Ordering[Long] = Ordering.Long.reverse

      def makeRequest(path: String) =
        F.fromEither(Uri.fromString(s"$reposUri/$path"))
          .flatMap(
            GET(
              _,
              config.accessToken
                .map(t => Authorization(Credentials.Token(CaseInsensitiveString("Bearer"), t.value)))
                .toSeq: _*
            )
          )

      def keyPath(key: RemoteCommandKey) = s"${key.device.value}/${key.name.value}$hexSuffix"

      def loadRepoPath(repo: Repo, path: String): OptionT[F, NonEmptyList[Content]] =
        OptionT(Trace[F].span("github.load.repo.path") {
          for {
            _ <- Trace[F]
              .putAll("repo.name" -> repo.name.value, "repo.owner" -> repo.owner.value, "path" -> path)
            req <- makeRequest(
              s"${repo.owner.value}/${repo.repo.value}/contents/$path${repo.ref.fold("")(r => s"?ref=${r.value}")}"
            )
            resp <- client.expectOption[Either[Content, NonEmptyList[Content]]](req)
          } yield resp.map(_.leftMap(NonEmptyList.one).merge)
        })

      def listRemote: F[List[(Repo, NonEmptyString, NonEmptyString, Content)]] =
        trace.span("github.list.remote") {
          reversedRepos.toList
            .parFlatTraverse[OptionT[F, *], (Repo, NonEmptyString, NonEmptyString, Content)] { repo =>
              loadRepoPath(repo, "")
                .flatMap { files =>
                  files
                    .filter(_.`type` == "dir")
                    .flatTraverse[OptionT[F, *], (Repo, NonEmptyString, NonEmptyString, Content)] { dir =>
                      loadRepoPath(repo, dir.path).map { files =>
                        files.filter(f => f.`type` == "file" && f.name.endsWith(hexSuffix)).flatMap { file =>
                          for {
                            device <- refineV[NonEmpty](dir.name).toOption
                            command <- refineV[NonEmpty](file.name.replace(hexSuffix, "")).toOption
                          } yield (repo, device, command, file)
                        }
                      }
                    }
                }
            }
            .value
            .map(_.toList.flatten)
        }

      def downloadData(repo: Repo, path: String): F[Option[(Content, CommandPayload)]] =
        trace.span("github.download.data") {
          loadRepoPath(repo, path).subflatMap {
            case NonEmptyList(file, Nil) if file.encoding.contains("base64") =>
              for {
                base64Value <- file.content
                hexValue <- Either
                  .catchNonFatal(
                    new String(Base64.getDecoder.decode(base64Value.replaceAll("\\s+", "")))
                      .replace("\n", "")
                  )
                  .toOption
              } yield file -> CommandPayload(hexValue)
            case _ => None
          }.value
        }

      def getCommit(repo: Repo, path: String): F[Option[List[Commit]]] =
        for {
          req <- makeRequest(s"${repo.owner.value}/${repo.repo.value}/commits?page=0&per_page=1&path=$path")
          resp <- client.expectOption[List[Commit]](req)
        } yield resp

      def cacheDownload(
        cache: LocalCache[F],
        repo: Repo,
        key: RemoteCommandKey,
        content: Content,
        payload: CommandPayload
      ): F[Unit] = trace.span("github.cache.download") {
        for {
          _ <- repoTraceKeys(repo)
          commits <- getCommit(repo, content.path)
          date = commits.toList.flatMap(_.map(_.commit.committer.date.toEpochMilli)).sorted.headOption.getOrElse(0L)
          _ <- cache.store(repo, date, key, payload)
        } yield ()
      }

      def downloadAndCache(cache: LocalCache[F], repo: Repo, key: RemoteCommandKey): F[Option[CommandPayload]] =
        trace.span("github.download.and.cache") {
          repoTraceKeys(repo) *> keyTraceKeys(key) *> downloadData(repo, keyPath(key)).flatMap {
            case Some((content, payload)) =>
              trace.put("command.present", true) *> cacheDownload(cache, repo, key, content, payload)
                .as(Some(payload))
            case None => trace.put("command.present", false).as(None)
          }
        }

      def repoTraceKeys(repo: Repo): F[Unit] =
        trace.putAll(
          "repo.name" -> repo.name.value,
          "repo.owner" -> repo.owner.value,
          "repo.repo" -> repo.repo.value,
          "repo.ref" -> repo.ref.fold("")(_.value)
        )

      def keyTraceKeys(key: RemoteCommandKey) =
        trace.putAll(
          "source.name" -> key.source.fold("")(_.name.value),
          "source.type" -> key.source.fold("")(_.`type`.value),
          "device" -> key.device.value,
          "command" -> key.name.value
        )

      def checkUpdates(cache: LocalCache[F]) =
        trace.span("github.check.updates") {
          cache.list.flatMap { data =>
            trace.put("commands", data.size) *> data.keys.toList.parTraverse {
              case (repo, key) =>
                trace.span("checkUpdate") {
                  downloadAndCache(cache, repo, key)
                }
            }
          }.void
        }

      def getValue(cache: LocalCache[F]): (Repo, RemoteCommandKey) => F[Option[CommandPayload]] = { (repo, key) =>
        cache
          .load(repo, key)
          .flatMap {
            case Some(command) => F.pure(List(command))
            case None => downloadAndCache(cache, repo, key).map(_.toList)
          }
          .map(_.headOption)
      }

      def updatePoller(cache: LocalCache[F]) =
        if (config.autoUpdate) {
          val log = logger.mapK(new (F ~> G) {
            override def apply[A](fa: F[A]): G[A] = Span.noop[G].use(provide.provide(fa))
          })

          def stream =
            fs2.Stream
              .fixedRate[G](config.gitPollInterval)
              .evalMap(_ => k.run("github.check.updates").use(provide.provide(checkUpdates(cache))))

          Concurrent[G]
            .background(
              stream
                .handleErrorWith(
                  th =>
                    fs2.Stream
                      .eval(log.error(th)("Github remote command source update poller failed, restarting process"))
                      .flatMap(_ => stream)
                )
                .compile
                .drain
            )
            .mapK(provide.liftK)
            .map(_ => ())
        } else {
          Resource.pure[F, Unit](())
        }

      for {
        repoListingPoller <- Resource.liftF(Slf4jLogger.fromName[F](s"githubRepoListingPoller")).flatMap { implicit l =>
          DataPoller.traced[F, G, RepoListing, () => F[RepoListing]]("github.repo.listing")(
            _ => listRemote,
            config.gitPollInterval,
            config.pollErrorThreshold,
            onUpdate,
            k
          )((getData, _) => getData)
        }

        localCache <- LocalCache[F, G](
          config.localCacheDir,
          config.cachePollInterval,
          config.pollErrorThreshold,
          onUpdate,
          k
        )

        _ <- updatePoller(localCache)

      } yield new GithubRemoteCommandConfigSource[F](repos, repoListingPoller, getValue(localCache))
  }

}
