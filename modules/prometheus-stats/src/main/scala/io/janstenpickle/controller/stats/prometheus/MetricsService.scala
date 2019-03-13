package io.janstenpickle.controller.stats.prometheus

import java.io.{StringWriter, Writer}

import cats.effect.Sync
import cats.syntax.flatMap._
import io.janstenpickle.catseffect.CatsEffect._
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import org.http4s.dsl.Http4sDsl
import org.http4s.{Header, HttpRoutes}

class MetricsService[F[_]: Sync](registry: CollectorRegistry = CollectorRegistry.defaultRegistry) extends Http4sDsl[F] {
  private def toText: F[String] = suspendErrors {
    val writer: Writer = new StringWriter()
    TextFormat.write004(writer, registry.metricFamilySamples())
    writer.toString
  }

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root =>
      toText.flatMap(Ok(_, Header("Content-Type", TextFormat.CONTENT_TYPE_004)))
  }
}
