package io.janstenpickle.controller.tplink

import java.io.{InputStream, OutputStream}
import java.net.Socket

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{parser, Json}
import io.janstenpickle.controller.tplink.Encryption.{decrypt, encryptWithHeader}
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.applicative._

import scala.concurrent.duration.FiniteDuration

trait TplinkDevice[F[_]] {
  def deviceName: NonEmptyString

  def sendCommand(command: String): F[Json]
}

object TplinkDevice {
  def apply[F[_]: ContextShift: Timer](
    name: NonEmptyString,
    host: NonEmptyString,
    port: PortNumber,
    commandTimeout: FiniteDuration,
    blocker: Blocker
  )(implicit F: Concurrent[F], errors: TplinkErrors[F]): TplinkDevice[F] = new TplinkDevice[F] {
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

    override def deviceName: NonEmptyString = name
  }
}
