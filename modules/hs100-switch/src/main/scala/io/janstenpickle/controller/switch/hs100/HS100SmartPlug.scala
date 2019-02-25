package io.janstenpickle.controller.switch.hs100

import java.io.{InputStream, OutputStream}
import java.net.Socket

import cats.effect.{ContextShift, Resource, Sync}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{parser, Decoder, Json}
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.switch.hs100.Encryption._
import io.janstenpickle.controller.switch.{State, Switch}

import scala.concurrent.ExecutionContext

class HS100SmartPlug[F[_]](config: HS100SmartPlug.Config, ec: ExecutionContext)(
  implicit F: Sync[F],
  cs: ContextShift[F],
  errors: HS100Errors[F]
) extends Switch[F] {
  import HS100SmartPlug._

  private def eval[A](fa: F[A]): F[A] = evalOn(fa, ec)

  def getInfo: F[Json] = eval(sendCommand(InfoCommand))

  override def name: NonEmptyString = config.name

  override def getState: F[State] =
    getInfo.flatMap { json =>
      val cursor = json.hcursor
        .downField(System)
        .downField("get_sysinfo")
        .downField("relay_state")

      cursor.focus.fold[F[State]](errors.missingJson(name, cursor.history))(
        _.as[State].fold(errors.decodingFailure(name, _), _.pure[F])
      )
    }

  override def switchOn: F[Unit] =
    eval(sendCommand(SwitchOnCommand)).flatMap(parseSetResponse)

  override def switchOff: F[Unit] =
    eval(sendCommand(SwitchOffCommand)).flatMap(parseSetResponse)

  private def parseSetResponse(json: Json): F[Unit] = {
    val cursor = json.hcursor.downField(System).downField("set_relay_state").downField("err_code")

    cursor.focus
      .fold[F[Unit]](errors.missingJson(name, cursor.history))(_.as[Int] match {
        case Right(0) => ().pure[F]
        case Right(err) => errors.command(name, err)
        case Left(err) => errors.decodingFailure(name, err)
      })
  }

  private def sendCommand(command: String): F[Json] = {
    val resource: Resource[F, (Socket, InputStream, OutputStream)] = Resource.make {
      suspendErrors {
        val socket = new Socket(config.host.value, config.port.value)
        val inputStream = socket.getInputStream
        val outputStream = socket.getOutputStream
        (socket, inputStream, outputStream)
      }
    } {
      case (s, is, os) =>
        suspendErrors {
          os.close()
          is.close()
          s.close()
        }
    }

    resource.use {
      case (_, inputStream, outputStream) =>
        for {
          _ <- suspendErrors(outputStream.write(encryptWithHeader(command)))
          response <- decrypt(inputStream)
          result <- parser.parse(response).fold[F[Json]](errors.decodingFailure(name, _), _.pure[F])
        } yield result
    }
  }
}

object HS100SmartPlug {
  implicit val stateDecoder: Decoder[State] = Decoder.decodeInt.map(State.parse)

  case class Config(name: NonEmptyString, host: NonEmptyString, port: PortNumber = refineMV(9999))

  def apply[F[_]: Sync: ContextShift: HS100Errors](config: Config, ec: ExecutionContext): HS100SmartPlug[F] =
    new HS100SmartPlug[F](config, ec)

  def apply[F[_]: Sync: ContextShift: HS100Errors](config: Config): Resource[F, HS100SmartPlug[F]] =
    cachedExecutorResource.map(apply[F](config, _))

  private val InfoCommand = """{"system":{"get_sysinfo":null}}"""
  private val SwitchOnCommand = """{"system":{"set_relay_state":{"state":1}}}}"""
  private val SwitchOffCommand = """{"system":{"set_relay_state":{"state":0}}}}"""

  private val System = "system"
}
