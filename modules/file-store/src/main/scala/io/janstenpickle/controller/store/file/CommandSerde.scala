package io.janstenpickle.controller.store.file

import cats.effect.Sync
import cats.syntax.functor._
import cats.{Functor, Invariant}
import io.janstenpickle.catseffect.CatsEffect._

trait CommandSerde[F[_], T] {
  def serialize(data: T): F[Array[Byte]]
  def deserialize(data: Array[Byte]): F[T]
}

object CommandSerde {
  def apply[F[_], T](implicit serde: CommandSerde[F, T]): CommandSerde[F, T] = serde

  implicit def stringCommandSerde[F[_]: Sync]: CommandSerde[F, String] = new CommandSerde[F, String] {
    override def serialize(data: String): F[Array[Byte]] = suspendErrors(data.getBytes)
    override def deserialize(data: Array[Byte]): F[String] = suspendErrors(new String(data))
  }

  implicit def commandSerdeInvariant[F[_]: Functor]: Invariant[CommandSerde[F, ?]] = new Invariant[CommandSerde[F, ?]] {
    override def imap[A, B](fa: CommandSerde[F, A])(f: A => B)(g: B => A): CommandSerde[F, B] =
      new CommandSerde[F, B] {
        override def serialize(data: B): F[Array[Byte]] = fa.serialize(g(data))
        override def deserialize(data: Array[Byte]): F[B] = fa.deserialize(data).map(f)
      }
  }
}
