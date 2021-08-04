package io.janstenpickle.controller.api.endpoint

import java.io.{FileInputStream, InputStream}
import java.nio.file.{Path => JPath}

import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}

class ControllerUi[F[_]: Sync](basePath: Option[JPath] = None) extends Http4sDsl[F] {

  private def resourceExists(path: String): F[Boolean] =
    Sync[F].blocking(getClass.getClassLoader.getResource(path)).map(_ != null).recover {
      case _: NullPointerException => false
    }

  private def fileExists(base: JPath, path: String): F[Boolean] =
    Sync[F].blocking(base.resolve(path).toFile.exists())

  private def fetchFile(base: JPath, path: String): F[Response[F]] =
    for {
      exists <- fileExists(base, path)
      response <- if (exists) readInput(Sync[F].delay(new FileInputStream(base.resolve(path).toFile))) else NotFound()
    } yield response

  private def fetchResource(name: String): F[Response[F]] = {
    val path = s"static/$name"
    for {
      exists <- resourceExists(path)
      response <- if (exists) readInput(Sync[F].delay(getClass.getClassLoader.getResourceAsStream(path)))
      else NotFound()
    } yield response
  }

  private def readInput(is: F[InputStream]): F[Response[F]] =
    Response(
      body = fs2.io
        .readInputStream(is, 200)
    ).pure[F]

  private def fetch(name: String): F[Response[F]] =
    basePath.fold(fetchResource(name))(fetchFile(_, name))

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root => fetch("index.html")
    case GET -> path => fetch(path.segments.mkString("/"))
  }
}
