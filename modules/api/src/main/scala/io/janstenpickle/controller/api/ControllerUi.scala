package io.janstenpickle.controller.api

import cats.effect.{ContextShift, Sync}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.janstenpickle.catseffect.CatsEffect._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}

import scala.concurrent.ExecutionContext

class ControllerUi[F[_]: Sync: ContextShift](ec: ExecutionContext) extends Http4sDsl[F] {

  private def resourceExists(path: String): F[Boolean] =
    suspendErrorsEvalOn(getClass.getClassLoader.getResource(path), ec).map(_ != null).recover {
      case _: NullPointerException => false
    }

  private def fetch(name: String): F[Response[F]] = {
    val path = s"static/$name"
    for {
      exists <- resourceExists(path)
      response <- if (exists) fetchResource(path) else NotFound()
    } yield response
  }

  private def fetchResource(path: String): F[Response[F]] =
    Response(
      body = fs2.io
        .readInputStream(suspendErrors(getClass.getClassLoader.getResourceAsStream(path)), 200, ec)
    ).pure[F]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root => fetch("index.html")
    case GET -> path => fetch(path.toList.mkString("/"))
  }
}
