package io.janstenpickle.controller.config.trace

import cats.Apply
import cats.syntax.apply._
import io.janstenpickle.controller.configsource.{ConfigSource, WritableConfigSource}
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

  def writable[F[_]: Apply, A, K](
    source: WritableConfigSource[F, A, K],
    name: String,
    `type`: String,
    extraFields: (String, TraceValue)*
  )(implicit trace: Trace[F]): WritableConfigSource[F, A, K] = new WritableConfigSource[F, A, K] {
    private def span[B](n: String)(k: F[B]): F[B] = trace.span(n) {
      trace.put(extraFields ++ Seq("source.name" -> StringValue(name), "source.type" -> StringValue(`type`)): _*) *> k
    }

    override def getConfig: F[A] = span("getConfig") {
      source.getConfig
    }
    override def setConfig(a: A): F[Unit] = span("setConfig") {
      source.setConfig(a)
    }

    override def mergeConfig(a: A): F[A] = span("mergeConfig") {
      source.mergeConfig(a)
    }

    override def deleteItem(key: K): F[A] = span("deleteItem") {
      source.deleteItem(key)
    }
  }
}
