package io.janstenpickle.controller.advertiser

import cats.effect.syntax.concurrent._
import cats.effect.{Async, Concurrent, Resource, Sync, Timer}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import javax.jmdns.{JmDNS, ServiceInfo}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object Advertiser {

  def apply[F[_]: Concurrent: Timer](
    host: String,
    port: PortNumber,
    serviceType: ServiceType,
    label: NonEmptyString = NonEmptyString("Controller")
  ): Resource[F, F[Unit]] =
    JmDNSResource[F](host).flatMap(apply[F](_, port, serviceType, label))

  def apply[F[_]: Concurrent: Timer](jmdns: JmDNS, port: PortNumber, serviceType: ServiceType): Resource[F, F[Unit]] =
    apply[F](jmdns, port, serviceType, NonEmptyString("Controller"))

  def apply[F[_]: Concurrent: Timer](
    jmdns: JmDNS,
    port: PortNumber,
    serviceType: ServiceType,
    label: NonEmptyString
  ): Resource[F, F[Unit]] = Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
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
