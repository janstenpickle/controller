package io.janstenpickle.controller.advertiser

import cats.ApplicativeError
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.instances.either._
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.traverse._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import javax.jmdns.JmDNS

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object Discoverer {
  case class Service(name: String, port: PortNumber, addresses: NonEmptyList[NonEmptyString])

  def findService[F[_]: Sync](
    host: String,
    serviceType: ServiceType,
    timeout: FiniteDuration = 10.seconds
  ): F[NonEmptyList[Service]] =
    JmDNSResource[F](host).use(findService[F](_, serviceType, timeout))

  def findService[F[_]: Sync](jmdns: JmDNS, serviceType: ServiceType): F[NonEmptyList[Service]] =
    findService[F](jmdns, serviceType, 10.seconds)

  def findService[F[_]: Sync](
    jmdns: JmDNS,
    serviceType: ServiceType,
    timeout: FiniteDuration
  ): F[NonEmptyList[Service]] =
    Sync[F]
      .delay(jmdns.list(serviceType.toString, timeout.toMillis).toList)
      .flatMap { infos =>
        ApplicativeError[F, Throwable].fromEither(
          infos
            .traverse { serviceInfo =>
              for {
                port <- PortNumber.from(serviceInfo.getPort)
                hosts <- serviceInfo.getInet4Addresses.toList
                  .traverse { address =>
                    NonEmptyString.from(address.getHostAddress)
                  }

                hostsNel <- NonEmptyList
                  .fromList(hosts)
                  .toRight(s"Could not find any addresses for service ${serviceInfo.getName}")

              } yield Service(serviceInfo.getName, port, hostsNel)
            }
            .flatMap(NonEmptyList.fromList(_).toRight(s"Failed to discover any services of type $serviceType"))
            .leftMap(new RuntimeException(_) with NoStackTrace)
        )
      }

}
