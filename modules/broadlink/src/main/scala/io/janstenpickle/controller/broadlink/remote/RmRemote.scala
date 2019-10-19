package io.janstenpickle.controller.broadlink.remote

import cats.effect.{Blocker, ContextShift, Sync, Timer}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.github.mob41.blapi.{BLDevice, RM2Device}
import com.github.mob41.blapi.pkt.cmd.rm2.SendDataCmdPayload
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.broadlink.remote.RmRemoteConfig.{Mini3, RM2}
import io.janstenpickle.controller.model.CommandPayload
import io.janstenpickle.controller.remote.Remote
import io.janstenpickle.controller.remote.trace.TracedRemote
import javax.xml.bind.DatatypeConverter
import natchez.Trace

import scala.concurrent.duration._
import cats.syntax.applicativeError._

object RmRemote {
  def apply[F[_]: ContextShift](
    config: RmRemoteConfig,
    blocker: Blocker
  )(implicit F: Sync[F], timer: Timer[F], trace: Trace[F]): F[Remote[F, CommandPayload]] =
    (config match {
      case RM2(_, host, mac, _) => F.delay(new RM2Device(host.value, mac))
      case Mini3(_, host, mac, _) =>
        F.delay(BLDevice.createInstance(BLDevice.DEV_RM_MINI_3, host.value, mac).asInstanceOf[RM2Device])
    }).map { device =>
      TracedRemote(
        new Remote[F, CommandPayload] {
          override val name: NonEmptyString = config.name

          private def waitForPayload: F[Option[CommandPayload]] = F.tailRecM[Int, Option[CommandPayload]](10) {
            retries =>
              trace.span("waitForPayload") {
                if (retries == 0) F.pure(Right(None))
                else
                  blocker
                    .blockOn(timer.sleep(1.second) *> F.delay(Option(device.checkData())))
                    .recover { case _: ArrayIndexOutOfBoundsException => None }
                    .map(_.filter(_.length == 108).fold[Either[Int, Option[CommandPayload]]](Left(retries - 1)) {
                      data =>
                        Right(Some(CommandPayload(DatatypeConverter.printHexBinary(data))))
                    })
              }
          }

          override def learn: F[Option[CommandPayload]] =
            for {
              _ <- trace.span("auth") { blocker.delay(device.auth()) }
              learning <- trace.span("enterLearning") { blocker.delay(device.enterLearning()) }
              data <- if (learning) waitForPayload
              else F.pure(None)
              _ <- trace.put("payload.present" -> data.isDefined)
            } yield data

          override def sendCommand(payload: CommandPayload): F[Unit] =
            for {
              _ <- trace.span("auth") { blocker.delay(device.auth()) }
              _ <- trace.span("sendPacket") {
                blocker.delay(
                  device.sendCmdPkt(
                    config.timeoutMillis.value,
                    new SendDataCmdPayload(DatatypeConverter.parseHexBinary(payload.hexValue))
                  )
                )
              }
            } yield ()

        },
        "timeout.ms" -> config.timeoutMillis.value,
        "host" -> config.host.value,
        "mac" -> config.mac.getMacString,
        "manufacturer" -> "broadlink"
      )
    }
}
