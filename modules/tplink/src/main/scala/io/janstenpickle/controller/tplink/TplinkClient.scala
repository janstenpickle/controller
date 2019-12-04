package io.janstenpickle.controller.tplink

import java.io.{InputStream, OutputStream}
import java.net.Socket

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Decoder, Json, parser}
import io.janstenpickle.controller.tplink.Encryption.{decrypt, encryptWithHeader}
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.applicative._
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, State}
import io.janstenpickle.controller.tplink.Constants.{GetSysInfo, InfoCommand, System}

import scala.concurrent.duration.FiniteDuration

trait TplinkClient[F[_]] {
  def deviceName: NonEmptyString
  def sendCommand(command: String): F[Json]
  def parseSetResponse(key: String)(json: Json): F[Unit]
  def getState: F[State]
}

object TplinkClient {
  implicit final val stateDecoder: Decoder[State] = Decoder.decodeInt.map {
    case 1 => State.On
    case _ => State.Off
  }

  def apply[F[_]: ContextShift: Timer](
    name: NonEmptyString,
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

    def parseSetResponse(key: String)(json: Json): F[Unit] = {
      val cursor = json.hcursor.downField(System).downField(key).downField("err_code")

      cursor.focus
        .fold[F[Unit]](errors.missingJson(deviceName, cursor.history))(_.as[Int] match {
          case Right(0) => ().pure[F]
          case Right(err) => errors.command(deviceName, err)
          case Left(err) => errors.decodingFailure(deviceName, err)
        })
    }

    def getInfo: F[Json] = sendCommand(s"{$InfoCommand}")

    def getState: F[State] =
      getInfo.flatMap { json =>
        val cursor = json.hcursor
          .downField(System)
          .downField(GetSysInfo)
          .downField("relay_state")

        cursor.focus.fold[F[State]](errors.missingJson(deviceName, cursor.history))(
          _.as[State].fold(errors.decodingFailure(deviceName, _), _.pure[F])
        )
      }

    override def deviceName: NonEmptyString = name
  }
}
