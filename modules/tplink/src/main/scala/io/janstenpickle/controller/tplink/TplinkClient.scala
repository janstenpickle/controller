package io.janstenpickle.controller.tplink

import java.io.{InputStream, OutputStream}
import java.net.Socket

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{parser, Json}
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.tplink.Constants._
import io.janstenpickle.controller.tplink.Encryption.{decrypt, encryptWithHeader}

import scala.concurrent.duration.FiniteDuration

trait TplinkClient[F[_]] {
  def deviceName: NonEmptyString
  def deviceRoom: Option[Room]
  def sendCommand(command: String): F[Json]
  def parseSetResponse(key: String, subKey: String)(json: Json): F[Unit]
  def getInfo: F[Json]
  def rename(name: NonEmptyString, room: Option[Room]): F[Unit]
}

object TplinkClient {
  def apply[F[_]: ContextShift: Timer](
    name: NonEmptyString,
    room: Option[Room],
    host: NonEmptyString,
    port: PortNumber,
    commandTimeout: FiniteDuration,
    blocker: Blocker
  )(implicit F: Concurrent[F], errors: TplinkErrors[F]): TplinkClient[F] = new TplinkClient[F] {
    override def sendCommand(command: String): F[Json] = {
      val resource: Resource[F, (Socket, InputStream, OutputStream)] = Resource.make {
        F.delay {
          val socket = new Socket(host.value, port.value)
          val inputStream = socket.getInputStream
          val outputStream = socket.getOutputStream
          (socket, inputStream, outputStream)
        }
      } {
        case (s, is, os) =>
          F.delay {
            os.close()
            is.close()
            s.close()
          }
      }

      blocker.blockOn(resource.use {
        case (_, inputStream, outputStream) =>
          for {
            _ <- Concurrent.timeoutTo(
              F.delay(outputStream.write(encryptWithHeader(command))),
              commandTimeout,
              errors.tpLinkCommandTimedOut[Unit](name)
            )
            response <- Concurrent
              .timeoutTo(decrypt(inputStream), commandTimeout, errors.tpLinkCommandTimedOut[String](name))
            result <- parser.parse(response).fold[F[Json]](errors.decodingFailure(name, _), _.pure[F])
          } yield result
      })
    }

    def parseSetResponse(key: String, subKey: String)(json: Json): F[Unit] = {
      val cursor = json.hcursor.downField(key).downField(subKey).downField("err_code")

      cursor.focus
        .fold[F[Unit]](errors.missingJson(deviceName, cursor.history))(_.as[Int] match {
          case Right(0) => ().pure[F]
          case Right(err) => errors.command(deviceName, err)
          case Left(err) => errors.decodingFailure(deviceName, err)
        })
    }

    def getInfo: F[Json] = sendCommand(s"{$InfoCommand}")

    override def rename(name: NonEmptyString, room: Option[Room]): F[Unit] = {
      val devName = room.fold(name.value)(r => s"$name|$r")
      sendCommand(s"""{"$System":{"$SetDevAlias":"$devName"}}""").flatMap(parseSetResponse(System, SetDevAlias))
    }

    override def deviceName: NonEmptyString = name

    override def deviceRoom: Option[Room] = room
  }
}
