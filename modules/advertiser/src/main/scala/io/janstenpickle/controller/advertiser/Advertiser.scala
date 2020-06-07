package io.janstenpickle.controller.advertiser

import java.net.InetAddress

import cats.effect.syntax.concurrent._
import cats.effect.{Async, Concurrent, Resource, Sync, Timer}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import eu.timepit.refined.types.net.PortNumber
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import javax.jmdns.{JmDNS, ServiceInfo}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

object Advertiser {
  final val ServiceType: String = "_controller._tcp.local."

  def apply[F[_]: Concurrent: Timer](host: String, port: PortNumber): Resource[F, F[Unit]] =
    Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
      val resource = Resource
        .make(
          logger.info(s"Starting advertiser for host $host") >> Sync[F]
            .delay(JmDNS.create(InetAddress.getByName(host)))
        )(
          jmdns =>
            logger.info("Shutting down advertiser") >>
              Sync[F].delay {
                jmdns.unregisterAllServices()
                jmdns.close()
            }
        )
        .evalMap { jmdns =>
          Sync[F].delay {
            val props = Map.empty[String, String]

            val service = ServiceInfo
              .create(ServiceType, host, port.value, 1, 1, props.asJava)

            jmdns.registerService(service)
          }
        }
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
