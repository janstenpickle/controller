package io.janstenpickle.controller.api.trace

import natchez.{Kernel, TraceValue}
import org.http4s.{Header, Headers, Request, Response}

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

  def reqKernel[F[_]](req: Request[F]): Kernel =
    Kernel(req.headers.toList.map { h =>
      h.name.value -> h.value
    }.toMap)

  def kernelToHeaders(kernel: Kernel): List[Header] =
    kernel.toHeaders.toList.map {
      case (k, v) => Header(k, v)
    }
}
