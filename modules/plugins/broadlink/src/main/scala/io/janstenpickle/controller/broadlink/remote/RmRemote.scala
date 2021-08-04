package io.janstenpickle.controller.broadlink.remote

import cats.Eq
import cats.effect.kernel.{Async, Sync, Temporal}
import cats.instances.string._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.github.mob41.blapi.pkt.cmd.rm2.SendDataCmdPayload
import com.github.mob41.blapi.{BLDevice, RM2Device}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.broadlink.remote.RmRemoteConfig.{Mini3, RM2}
import io.janstenpickle.controller.model.CommandPayload
import io.janstenpickle.controller.remote.Remote
import io.janstenpickle.trace4cats.inject.Trace

import javax.xml.bind.DatatypeConverter
import scala.concurrent.duration._

trait RmRemote[F[_]] extends Remote[F, CommandPayload] {
  def host: String
  def mac: String
}

object RmRemote {
  implicit def rmRemoteEq[F[_]]: Eq[RmRemote[F]] = Eq.by { dev =>
    s"${dev.host}_${dev.mac}"
  }

  def fromDevice[F[_]](deviceName: NonEmptyString, timeout: FiniteDuration, device: RM2Device)(
    implicit F: Async[F],
    trace: Trace[F]
  ): RmRemote[F] =
    new RmRemote[F] {
      override val name: NonEmptyString = deviceName

      override def host: String = device.getHost

      override def mac: String = device.getMac.getMacString

      private def waitForPayload: F[Option[CommandPayload]] = F.tailRecM[Int, Option[CommandPayload]](5) { retries =>
        trace.span("waitForPayload") {
          if (retries == 0) F.pure(Right(None))
          else
            Temporal[F]
              .sleep(10.seconds) >> Sync[F]
              .blocking(Option(device.checkData()))
              .recover { case _: ArrayIndexOutOfBoundsException => None }
              .map(_.fold[Either[Int, Option[CommandPayload]]](Left(retries - 1)) { data =>
                println(DatatypeConverter.printHexBinary(data))
                Right(Some(CommandPayload(DatatypeConverter.printHexBinary(data))))
              })
        }
      }
      override def learn: F[Option[CommandPayload]] =
        for {
          _ <- trace.span("auth") { Sync[F].blocking(device.auth()) }
          learning <- trace.span("enterLearning") { Sync[F].blocking(device.enterLearning()) }
          data <- if (learning) waitForPayload
          else F.pure(None)
          _ <- trace.put("payload.present", data.isDefined)
        } yield data

      override def sendCommand(payload: CommandPayload): F[Unit] =
        for {
          _ <- trace.span("auth") { Sync[F].blocking(device.auth()) }
          _ <- trace.span("sendPacket") {
            Sync[F].blocking(
              device.sendCmdPkt(
                timeout.toMillis.toInt,
                new SendDataCmdPayload(DatatypeConverter.parseHexBinary(payload.hexValue))
              )
            )
          }
        } yield ()

    }

  def apply[F[_]](config: RmRemoteConfig)(implicit F: Async[F], trace: Trace[F]): F[RmRemote[F]] =
    (config match {
      case RM2(_, host, mac, _) => F.delay(new RM2Device(host.value, mac))
      case Mini3(_, host, mac, _) =>
        F.delay(BLDevice.createInstance(BLDevice.DEV_RM_MINI_3, host.value, mac).asInstanceOf[RM2Device])
    }).map { device =>
      fromDevice(config.name, config.timeout, device)
    }
}
