package io.janstenpickle.controller.advertiser

import cats.effect.kernel.Outcome
import cats.effect.syntax.spawn._
import cats.effect.{Async, Resource, Sync}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

import javax.jmdns.{JmDNS, ServiceInfo}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object Advertiser {

  def apply[F[_]: Async](
    host: String,
    port: PortNumber,
    serviceType: ServiceType,
    label: NonEmptyString = NonEmptyString("Controller")
  ): Resource[F, F[Outcome[F, Throwable, Unit]]] =
    JmDNSResource[F](host).flatMap(apply[F](_, port, serviceType, label))

  def apply[F[_]: Async](
    jmdns: JmDNS,
    port: PortNumber,
    serviceType: ServiceType
  ): Resource[F, F[Outcome[F, Throwable, Unit]]] =
    apply[F](jmdns, port, serviceType, NonEmptyString("Controller"))

  def apply[F[_]: Async](
    jmdns: JmDNS,
    port: PortNumber,
    serviceType: ServiceType,
    label: NonEmptyString
  ): Resource[F, F[Outcome[F, Throwable, Unit]]] = Resource.eval(Slf4jLogger.create[F]).flatMap { logger =>
    val resource = Resource
      .make(logger.info(s"Starting advertiser for $label") >> Sync[F].delay {
        val props = Map.empty[String, String]

        val service = ServiceInfo
          .create(serviceType.toString, label.value, port.value, 1, 1, props.asJava)

        jmdns.registerService(service)

        service
      })(service => logger.info("Shutting down advertiser") >> Sync[F].delay(jmdns.unregisterService(service)))
      .use(_ => Async[F].never[Unit])

    Stream
      .retry(resource.onError {
        case th => logger.error(th)("Bonjour advertiser failed")
      }, 5.seconds, _ + 1.second, Int.MaxValue)
      .compile
      .drain
      .background
  }
}
