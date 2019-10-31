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
import cats.{Functor, Parallel}
import eu.timepit.refined.cats._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.{refineMV, refineV}
import github4s.Github
import github4s.Github._
import github4s.GithubResponses.{GHIO, GHResponse}
import github4s.free.domain.{Content, Pagination}
import github4s.free.interpreters.{Capture, Interpreters}
import github4s.jvm.Implicits._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.model.{CommandPayload, RemoteCommandKey}
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.remotecontrol.git.GithubRemoteCommandConfigSource._
import natchez.Trace
import scalaj.http.HttpResponse

import scala.concurrent.duration._

class GithubRemoteCommandConfigSource[F[_]: Parallel](
  github: Github,
  repos: NonEmptyList[Repo],
  listRemote: () => F[RepoListing],
  gv: (Repo, RemoteCommandKey) => F[Option[CommandPayload]]
)(implicit F: Sync[F], trace: Trace[F])
    extends ConfigSource[F, RemoteCommandKey, CommandPayload] {
  private implicit val capture: Capture[F] = new Capture[F] {
    override def capture[A](a: => A): F[A] = F.delay(a)
  }

  override implicit def functor: Functor[F] = F

  private implicit val interpreters: Interpreters[F, HttpResponse[String]] = new Interpreters[F, HttpResponse[String]]()

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

  case class Config(
    repos: List[Repo] = List.empty,
    accessToken: Option[NonEmptyString] = None,
    localCacheDir: Path = Paths.get("/", "tmp", "controller", "cache", "remote-command"),
    gitPollInterval: FiniteDuration = 1.hour,
    cachePollInterval: FiniteDuration = 30.seconds,
    pollErrorThreshold: PosInt = PosInt(3),
    autoUpdate: Boolean = true
  )

  type RepoListing = List[(Repo, NonEmptyString, NonEmptyString, Content)]

  private final val hexSuffix = ".hex"

  def apply[F[_]: Parallel, G[_]: Concurrent: Timer](config: Config, onUpdate: Any => F[Unit])(
    implicit F: Sync[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, GithubRemoteCommandConfigSource[F]] = {
    val github = Github(config.accessToken.map(_.value))
    lazy val repos = NonEmptyList(DefaultRepo, config.repos)
    lazy val reversedRepos = repos.reverse

    implicit val capture: Capture[F] = new Capture[F] {
      override def capture[A](a: => A): F[A] = F.delay(a)
    }

    implicit val interpreters: Interpreters[F, HttpResponse[String]] = new Interpreters[F, HttpResponse[String]]()

    implicit val epochOrder: Ordering[Long] = Ordering.Long.reverse

    def exec[A](a: GHIO[GHResponse[A]]): F[Option[A]] =
      trace.span("execGithubOp") {
        a.exec[F, HttpResponse[String]]().flatMap {
          case Left(th) =>
            if (th.getMessage.contains("404")) trace.put("error" -> false).as(None)
            else trace.put("error" -> true) *> F.raiseError(th)
          case Right(r) => trace.put("error" -> false).as(Some(r.result))
        }
      }

    def keyPath(key: RemoteCommandKey) = s"${key.device.value}/${key.name.value}$hexSuffix"

    def loadRepoPath(repo: Repo, path: String): OptionT[F, NonEmptyList[Content]] =
      OptionT(trace.span("loadRepoPath") {
        repoTraceKeys(repo) *> trace.put("path" -> path) *> exec(
          github.repos
            .getContents(repo.owner.value, repo.repo.value, path, repo.ref.map(_.value))
        )
      })

    def listRemote: F[List[(Repo, NonEmptyString, NonEmptyString, Content)]] =
      trace.span("listRemoteGithub") {
        reversedRepos.toList
          .parFlatTraverse[OptionT[F, *], (Repo, NonEmptyString, NonEmptyString, Content)] { repo =>
            loadRepoPath(repo, "")
              .flatMap { files =>
                files
                  .filter(_.`type` == "dir")
                  .parFlatTraverse[OptionT[F, *], (Repo, NonEmptyString, NonEmptyString, Content)] { dir =>
                    loadRepoPath(repo, dir.path).map { files =>
                      files.filter(f => f.`type` == "file" && f.name.endsWith(hexSuffix)).flatMap { file =>
                        for {
                          device <- refineV[NonEmpty](dir.name).toOption
                          command <- refineV[NonEmpty](file.name.replaceAllLiterally(hexSuffix, "")).toOption
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
      trace.span("downloadDataFromGithub") {
        loadRepoPath(repo, path).subflatMap {
          case NonEmptyList(file, Nil) if file.encoding.contains("base64") =>
            for {
              base64Value <- file.content
              hexValue <- Either
                .catchNonFatal(
                  new String(Base64.getDecoder.decode(base64Value.replaceAll("\\s+", ""))).replaceAllLiterally("\n", "")
                )
                .toOption
            } yield file -> CommandPayload(hexValue)
          case _ => None
        }.value
      }

    def cacheDownload(
      cache: LocalCache[F],
      repo: Repo,
      key: RemoteCommandKey,
      content: Content,
      payload: CommandPayload
    ): F[Unit] = trace.span("cacheGithubDownload") {
      for {
        _ <- repoTraceKeys(repo)
        commits <- exec(
          github.repos
            .listCommits(
              repo.owner.value,
              repo.repo.value,
              path = Some(content.path),
              pagination = Some(Pagination(0, 1))
            )
        )
        dates <- commits.toList.flatten.parTraverse { commit =>
          F.delay(Instant.parse(commit.date).toEpochMilli)
        }
        _ <- cache.store(repo, dates.sorted.headOption.getOrElse(0L), key, payload)
      } yield ()
    }

    def downloadAndCache(cache: LocalCache[F], repo: Repo, key: RemoteCommandKey): F[Option[CommandPayload]] =
      trace.span("downloadAndCache") {
        repoTraceKeys(repo) *> keyTraceKeys(key) *> downloadData(repo, keyPath(key)).flatMap {
          case Some((content, payload)) =>
            trace.put("command.present" -> true) *> cacheDownload(cache, repo, key, content, payload).as(Some(payload))
          case None => trace.put("command.present" -> false).as(None)
        }
      }

    def repoTraceKeys(repo: Repo): F[Unit] =
      trace.put(
        "repo.name" -> repo.name.value,
        "repo.owner" -> repo.owner.value,
        "repo.repo" -> repo.repo.value,
        "repo.ref" -> repo.ref.fold("")(_.value)
      )

    def keyTraceKeys(key: RemoteCommandKey) =
      trace.put(
        "source.name" -> key.source.fold("")(_.name.value),
        "source.type" -> key.source.fold("")(_.`type`.value),
        "device" -> key.device.value,
        "command" -> key.name.value
      )

    def checkUpdates(cache: LocalCache[F]) =
      trace.span("checkUpdates") {
        cache.list.flatMap { data =>
          trace.put("commands" -> data.size) *> data.keys.toList.parTraverse {
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

    def updatePoller(cache: LocalCache[F], logger: Logger[F]) =
      if (config.autoUpdate) {
        val log = logger.mapK(liftLower.lower)

        def stream =
          fs2.Stream
            .fixedRate[G](config.gitPollInterval)
            .evalMap(_ => liftLower.lower("githubCheckUpdates")(checkUpdates(cache)))
        Resource
          .make(
            Concurrent[G].start(
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
          )(f => Sync[G].suspend(f.cancel))
          .mapK(liftLower.lift)
          .map(_ => ())
      } else {
        Resource.pure[F, Unit](())
      }

    for {
      logger <- Resource.liftF(Slf4jLogger.fromClass[F](this.getClass))

      repoListingPoller <- DataPoller.traced[F, G, RepoListing, () => F[RepoListing]]("githubtRepoListing")(
        _ => listRemote,
        config.gitPollInterval,
        config.pollErrorThreshold,
        onUpdate
      )((getData, _) => getData)

      localCache <- LocalCache[F, G](
        config.localCacheDir,
        config.cachePollInterval,
        config.pollErrorThreshold,
        onUpdate
      )

      _ <- updatePoller(localCache, logger)

    } yield new GithubRemoteCommandConfigSource[F](github, repos, repoListingPoller, getValue(localCache))
  }

}
