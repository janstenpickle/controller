package io.janstenpickle.controller.config.trace

import cats.syntax.apply._
import cats.{Applicative, Apply, Functor}
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource, WritableConfigSource}
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

object TracedConfigSource {
  def apply[F[_]: Applicative, K, V](
    source: ConfigSource[F, K, V],
    name: String,
    `type`: String,
    extraFields: (String, TraceValue)*
  )(implicit trace: Trace[F]): ConfigSource[F, K, V] = new ConfigSource[F, K, V] {
    override def functor: Functor[F] = Functor[F]

    private def span[A](n: String)(k: F[A]): F[A] = trace.span(n) {
      trace
        .put(extraFields ++ Seq("source.name" -> StringValue(name), "source.type" -> StringValue(`type`)): _*) *> k
    }

    override def getConfig: F[ConfigResult[K, V]] = span("getConfig") {
      source.getConfig
    }

    override def getValue(key: K): F[Option[V]] = span("getValue") {
      source.getValue(key)
    }
  }

  def writable[F[_]: Apply, K, V](
    source: WritableConfigSource[F, K, V],
    name: String,
    `type`: String,
    extraFields: (String, TraceValue)*
  )(implicit trace: Trace[F]): WritableConfigSource[F, K, V] = new WritableConfigSource[F, K, V] {
    override def functor: Functor[F] = Functor[F]

    private def span[B](n: String)(k: F[B]): F[B] = trace.span(n) {
      trace.put(extraFields ++ Seq("source.name" -> StringValue(name), "source.type" -> StringValue(`type`)): _*) *> k
    }

    override def getConfig: F[ConfigResult[K, V]] = span("getConfig") {
      source.getConfig
    }
    override def setConfig(a: Map[K, V]): F[Unit] = span("setConfig") {
      source.setConfig(a)
    }

    override def mergeConfig(a: Map[K, V]): F[ConfigResult[K, V]] = span("mergeConfig") {
      source.mergeConfig(a)
    }

    override def deleteItem(key: K): F[ConfigResult[K, V]] = span("deleteItem") {
      source.deleteItem(key)
    }

    override def upsert(key: K, value: V): F[ConfigResult[K, V]] = span("upsert") {
      source.upsert(key, value)
    }

    override def getValue(key: K): F[Option[V]] = span("getValue") {
      source.getValue(key)
    }

    override def listKeys: F[Set[K]] = span("listKeys") {
      source.listKeys
    }
  }
}
