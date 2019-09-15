package io.janstenpickle.controller.config.trace

import cats.Apply
import cats.syntax.apply._
import io.janstenpickle.controller.configsource.ConfigSource
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

object TracedConfigSource {
  def apply[F[_]: Apply, A](
    source: ConfigSource[F, A],
    name: String,
    `type`: String,
    extraFields: (String, TraceValue)*
  )(implicit trace: Trace[F]): ConfigSource[F, A] = new ConfigSource[F, A] {
    override def getConfig: F[A] = trace.span("getConfig") {
      trace
        .put(extraFields ++ Seq("source.name" -> StringValue(name), "source.type" -> StringValue(`type`)): _*) *> source.getConfig
    }
  }
}
