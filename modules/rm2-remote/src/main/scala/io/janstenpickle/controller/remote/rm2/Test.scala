package io.janstenpickle.controller.remote.rm2

import cats.effect.{ExitCode, IO, IOApp}
import com.github.mob41.blapi.mac.Mac
import eu.timepit.refined._
import io.janstenpickle.controller.model.CommandPayload
import io.janstenpickle.controller.remote.rm2.Rm2Remote.Config
import javax.xml.bind.DatatypeConverter

object Test extends IOApp {
  val config = Rm2Remote.Config(refineMV("lounge"), refineMV("192.168.1.109"), new Mac("34:EA:34:B5:2E:D2"))

  val device = Rm2Remote[IO](config)

  override def run(args: List[String]): IO[ExitCode] =
    device
      .use(
        remote =>
          for {
//            data <- remote.learn
//            _ = println(data.get.hexValue)
            // _ <- remote.sendCommand(data.get)
            _ <- remote.sendCommand(
              CommandPayload(
                "260050000001299214111336131213121335141212371336133613111435143514121335143613121311141114351435143514121312123614361336131114111411133613361312130005020001284714000d050000000000000000"
              )
            )
          } yield ()
      )
      .map(_ => ExitCode.Success)
}
