package io.janstenpickle.controller.kodi

import cats.Eq
import cats.effect.kernel.Concurrent
import cats.instances.string._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.generic.auto._
import io.circe.{Json, JsonObject}
import org.http4s.Uri
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.Http4sDsl

trait KodiClient[F[_]] {
  def name: NonEmptyString
  def send(command: String): F[Json] = send(command, Json.fromJsonObject(JsonObject.empty))
  def send(command: String, params: Json): F[Json]
}

object KodiClient {
  implicit def kodiClientEq[F[_]]: Eq[KodiClient[F]] = Eq.by(_.name.value)

  case class Error(code: Int, message: String)
  case class Result(error: Option[Error], result: Option[Json])

  private final def container(method: String, params: Json): Json =
    Json.fromFields(
      Map(
        "jsonrpc" -> Json.fromString("2.0"),
        "id" -> Json.fromInt(1),
        "method" -> Json.fromString(method),
        "params" -> params
      )
    )

  def apply[F[_]](client: Client[F], kodi: NonEmptyString, host: NonEmptyString, port: PortNumber)(
    implicit F: Concurrent[F],
    errors: KodiErrors[F]
  ): F[KodiClient[F]] =
    F.fromEither(Uri.fromString(s"http://${host.value}:${port.value}/jsonrpc")).map { uri =>
      new KodiClient[F] with Http4sDsl[F] with Http4sClientDsl[F] {
        override def name: NonEmptyString = kodi

        override def send(command: String, params: Json): F[Json] =
          client
            .expect[Json](POST(container(command, params), uri))
            .flatMap(_.as[Result] match {
              case Right(Result(None, Some(json))) => F.pure(json)
              case Right(Result(Some(error), None)) =>
                errors.rpcError[Json](kodi, host, port, error.code, error.message)
              case Right(_) => errors.missingResult[Json](kodi, host, port)
              case Left(err) => F.raiseError(err)
            })
      }
    }

}
