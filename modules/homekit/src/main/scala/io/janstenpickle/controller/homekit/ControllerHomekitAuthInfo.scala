package io.janstenpickle.controller.homekit

import java.math.BigInteger
import java.util.Base64

import cats.derived.auto.eq._
import cats.effect.{Concurrent, Resource, Timer}
import cats.instances.string._
import cats.instances.option._
import cats.instances.map._
import cats.kernel.Eq
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, ApplicativeError, Id}
import eu.timepit.refined.types.numeric.PosInt
import extruder.core.{Parser, Show}
import extruder.typesafe._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.generic.extras.Configuration
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.extras.semiauto._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import io.circe.syntax._
import io.github.hapjava.server.HomekitAuthInfo
import io.github.hapjava.server.impl.HomekitServer
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue
import io.janstenpickle.trace4cats.model.AttributeValue.LongValue

import scala.concurrent.duration._
import scala.util.Try

object ControllerHomekitAuthInfo {
  private implicit def byteArrayDecoder: Decoder[Array[Byte]] = Decoder.decodeString.emapTry { str =>
    Try(Base64.getDecoder.decode(str))
  }
  private implicit def byteArrayEncoder: Encoder[Array[Byte]] =
    Encoder.encodeString.contramap(Base64.getEncoder.encodeToString(_))
  private implicit def bigIntDecoder: Decoder[BigInteger] = byteArrayDecoder.emapTry { data =>
    Try(new BigInteger(data))
  }
  private implicit def bigIntEncoder: Encoder[BigInteger] = byteArrayEncoder.contramap(_.toByteArray)

  private implicit def authInfoEmpty: Empty[AuthInfo] = Empty(AuthInfo(None, None, None, Map.empty))

  private implicit def bigIntegerEq: Eq[BigInteger] = Eq.by(_.toByteArray)
  private implicit def byteArrayEq: Eq[Array[Byte]] = Eq.by(Base64.getEncoder.encodeToString(_))

  case class AuthInfo(
    mac: Option[String],
    salt: Option[BigInteger],
    privateKey: Option[Array[Byte]],
    users: Map[String, Array[Byte]] = Map.empty
  )

  implicit val config: Configuration = io.janstenpickle.controller.model.circeConfig
  implicit val authInfoCodec: Codec[AuthInfo] = deriveConfiguredCodec

  case class Config(pollInterval: FiniteDuration = 30.seconds, errorThreshold: PosInt = PosInt(3))

  def apply[F[_], G[_]: Concurrent: Timer](configFile: ConfigFileSource[F], pollConfig: Config, fk: F ~> Id)(
    implicit F: Concurrent[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, HomekitAuthInfo] = {
    def load(current: Data[AuthInfo]): F[AuthInfo] =
      for {
        config <- configFile.configs.map(_.json)
        authInfo <- ApplicativeError[F, Throwable].fromEither(config.as[AuthInfo])
      } yield authInfo

    def write(authInfo: AuthInfo): F[Unit] =
      configFile.write(authInfo.asJson)

    def formatUsername(username: String): String = username.replace(":", "").replace("-", "").toLowerCase()

    Resource.liftF(Slf4jLogger.create[F]).flatMap { implicit logger =>
      DataPoller.traced[F, G, AuthInfo, HomekitAuthInfo]("homekit.authinfo")(
        load(_),
        pollConfig.pollInterval,
        pollConfig.errorThreshold,
        (_: AuthInfo, _: AuthInfo) => F.unit
      ) { (get, update) =>
        new HomekitAuthInfo {

          private def span[A](name: String, extraFields: (String, AttributeValue)*)(k: F[A]): F[A] =
            trace.span(s"homekit.authinfo.$name") {
              trace.putAll(extraFields: _*) *> k
            }

          override def getPin: String = "324-64-932"

          override def getMac: String =
            fk(span("get.mac") {
              get().flatMap { authInfo =>
                authInfo.mac match {
                  case None =>
                    span("generate.mac") {
                      val mac = HomekitServer.generateMac()
                      val updated = authInfo.copy(mac = Some(mac))
                      write(updated) *> trace.put("mac", mac) *> update(updated).as(mac)
                    }
                  case Some(v) => F.pure(v)
                }
              }
            })

          override def getSalt: BigInteger =
            fk(span("get.salt") {
              get().flatMap { authInfo =>
                authInfo.salt match {
                  case None =>
                    span("generate.salt") {
                      val salt = HomekitServer.generateSalt()
                      val updated = authInfo.copy(salt = Some(salt))
                      write(updated) *> trace.put("salt", LongValue(salt.longValueExact())) *> update(updated)
                        .as(salt)
                    }
                  case Some(v) => F.pure(v)
                }
              }
            })

          override def getPrivateKey: Array[Byte] =
            fk(span("get.private.key") {
              get().flatMap { authInfo =>
                authInfo.privateKey match {
                  case None =>
                    span("generate.private.key") {
                      val pk = HomekitServer.generateKey()
                      val updated = authInfo.copy(privateKey = Some(pk))
                      write(updated) *> update(updated).as(pk)
                    }
                  case Some(v) => F.pure(v)
                }
              }
            })

          override def createUser(username: String, publicKey: Array[Byte]): Unit =
            fk(span("create.user", "username" -> username) {
              get().flatMap { authInfo =>
                val updated = authInfo.copy(users = authInfo.users.updated(formatUsername(username), publicKey))
                write(updated) *> update(updated)
              }
            })

          override def removeUser(username: String): Unit =
            fk(span("remove.user", "username" -> username) {
              get().flatMap { authInfo =>
                val updated = authInfo.copy(users = authInfo.users.filterKeys(_ != formatUsername(username)).toMap)

                write(updated) *> update(updated)
              }
            })

          override def getUserPublicKey(username: String): Array[Byte] =
            fk(span("get.user", "username" -> username) {
              get().flatMap { authInfo =>
                F.delay(authInfo.users(formatUsername(username)))
              }
            })
        }
      }
    }
  }
}
