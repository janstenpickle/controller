package io.janstenpickle.controller.remote.rm2

import cats.effect.{ContextShift, Resource, Sync, Timer}
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.github.mob41.blapi.RM2Device
import com.github.mob41.blapi.mac.Mac
import com.github.mob41.blapi.pkt.cmd.rm2.SendDataCmdPayload
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined._
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.model.CommandPayload
import io.janstenpickle.controller.remote.Remote

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import cats.syntax.apply._
import io.janstenpickle.controller.remote.rm2.RmRemoteConfig.{Mini3, Rm2}
import javax.xml.bind.DatatypeConverter

object RmRemote {
  def apply[F[_]: Sync: ContextShift: Timer](config: RmRemoteConfig): Resource[F, Remote[F, CommandPayload]] =
    cachedExecutorResource.evalMap(apply(config, _))

  def apply[F[_]](
    config: RmRemoteConfig,
    ec: ExecutionContext
  )(implicit F: Sync[F], cs: ContextShift[F], timer: Timer[F]): F[Remote[F, CommandPayload]] = {
    def suspendErrorsEval[A](fa: A): F[A] = suspendErrorsEvalOn(fa, ec)

    (config match {
      case Rm2(_, host, mac, _) => suspendErrors(new RM2Device(host.value, mac))
      case Mini3(_, _, _, _) =>
        F.raiseError[RM2Device](new RuntimeException("Could not create Mini 3 device - not yet supported"))
    }).map { device =>
      new Remote[F, CommandPayload] {
        override def name: NonEmptyString = config.name

        override def learn: F[Option[CommandPayload]] =
          for {
            _ <- suspendErrorsEval(device.auth())
            learning <- suspendErrorsEval(device.enterLearning())
            data <- if (learning)
              evalOn(timer.sleep(5.seconds) *> suspendErrors(Option(device.checkData())), ec)
                .map(_.map(data => CommandPayload(DatatypeConverter.printHexBinary(data))))
            else F.pure(None)
          } yield data

        override def sendCommand(payload: CommandPayload): F[Unit] =
          suspendErrorsEval(device.auth()) *> suspendErrorsEval(
            device.sendCmdPkt(
              config.timeoutMillis.value,
              new SendDataCmdPayload(DatatypeConverter.parseHexBinary(payload.hexValue))
            )
          )
      }
    }
  }
}
