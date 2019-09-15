package io.janstenpickle.controller.store.file

import cats.effect.Sync
import cats.syntax.functor._
import cats.{Functor, Invariant}

trait CommandSerde[F[_], T] { outer =>
  def serialize(data: T): F[Array[Byte]]
  def deserialize(data: Array[Byte]): F[T]
  def imap[A](f: T => A)(g: A => T)(implicit F: Functor[F]): CommandSerde[F, A] = new CommandSerde[F, A] {
    override def serialize(data: A): F[Array[Byte]] = outer.serialize(g(data))
    override def deserialize(data: Array[Byte]): F[A] = outer.deserialize(data).map(f)
  }
}

object CommandSerde {
  def apply[F[_], T](implicit serde: CommandSerde[F, T]): CommandSerde[F, T] = serde

  implicit def stringCommandSerde[F[_]: Sync]: CommandSerde[F, String] = new CommandSerde[F, String] {
    override def serialize(data: String): F[Array[Byte]] = Sync[F].delay(data.getBytes)
    override def deserialize(data: Array[Byte]): F[String] = Sync[F].delay(new String(data))
  }

  implicit def commandSerdeInvariant[F[_]: Functor]: Invariant[CommandSerde[F, *]] = new Invariant[CommandSerde[F, *]] {
    override def imap[A, B](fa: CommandSerde[F, A])(f: A => B)(g: B => A): CommandSerde[F, B] = fa.imap(f)(g)
  }
}
