//package io.janstenpickle.controller.switch.hs100
//
//import cats.effect.{ExitCode, IO, IOApp}
//import eu.timepit.refined._
//import io.circe
//import io.circe.CursorOp
//
//object Test extends IOApp {
//  implicit val errors = new HS100Errors[IO] {
//    def raise[A](msg: String): IO[A] = IO.raiseError(new RuntimeException(msg))
//
//    override def decodingFailure[A](error: circe.Error): IO[A] = IO.raiseError(error)
//
//    override def missingJson[A](history: List[CursorOp]): IO[A] = raise(history.mkString(","))
//
//    override def command[A](errorCode: Int): IO[A] = raise(errorCode.toString)
//  }
//
//  val client = HS100SmartPlug[IO](HS100SmartPlug.Config(refineMV(") refineMV("192.168.1.103")))
//
//  override def run(args: List[String]): IO[ExitCode] =
//    client.use(_.getInfo).map(println).map(_ => ExitCode.Success)
//}
