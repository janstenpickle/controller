package io.janstenpickle.controller.advertiser

import java.net.InetAddress

import cats.effect.{Resource, Sync}
import javax.jmdns.JmDNS

object JmDNSResource {
  def apply[F[_]: Sync](host: String): Resource[F, JmDNS] =
    Resource.make(
      Sync[F]
        .delay(JmDNS.create(InetAddress.getByName(host)))
    )(jmdns => Sync[F].delay(jmdns.close()))
}
