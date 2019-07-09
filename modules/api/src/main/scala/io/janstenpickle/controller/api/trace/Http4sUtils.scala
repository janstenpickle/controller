package io.janstenpickle.controller.api.trace

import natchez.TraceValue
import org.http4s.{Headers, Request, Response}

object Http4sUtils {
  def headerMap(headers: Headers, `type`: String): List[(String, TraceValue)] = headers.toList.map { h =>
    s"${`type`}.header.${h.name.value}" -> TraceValue.stringToTraceValue(h.value)
  }

  def requestFields[F[_]](req: Request[F]): List[(String, TraceValue)] =
    List[(String, TraceValue)]("http.method" -> req.method.name, "http.url" -> req.uri.path) ++ headerMap(
      req.headers,
      "req"
    )

  def responseFields[F[_]](resp: Response[F]): List[(String, TraceValue)] =
    List[(String, TraceValue)]("http.status_code" -> resp.status.code, "http.status_message" -> resp.status.reason) ++ headerMap(
      resp.headers,
      "resp"
    )
}
