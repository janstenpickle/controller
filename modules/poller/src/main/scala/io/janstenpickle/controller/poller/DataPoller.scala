package io.janstenpickle.controller.poller

import java.util.concurrent.TimeUnit

import cats.effect._
import cats.effect.concurrent.Ref
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Monoid}
import eu.timepit.refined.types.numeric.PosInt

import scala.concurrent.duration.FiniteDuration

object DataPoller {
  case class Data[A](value: A, updated: Long, errorCount: Int = 0, error: Option[Throwable] = None)

  private def timeNow[F[_]](implicit timer: Timer[F]): F[Long] = timer.clock.realTime(TimeUnit.MILLISECONDS)

  private def poller[F[_], A](getData: Data[A] => F[A], pollInterval: FiniteDuration, dataRef: Ref[F, Data[A]])(
    implicit F: Concurrent[F],
    timer: Timer[F]
  ): F[Fiber[F, Unit]] = {

    def update(now: Long): F[Unit] =
      (for {
        current <- dataRef.get
        data <- getData(current)
        _ <- dataRef.set(Data(data, now))
      } yield
        ()).handleErrorWith(th => dataRef.tryUpdate(d => d.copy(errorCount = d.errorCount + 1, error = Some(th))).void)

    def loop(now: Long): F[Unit] = (update(now) *> timer.sleep(pollInterval)) *> timeNow.flatMap(loop)

    F.start(timeNow.flatMap(loop))
  }

  private def reader[F[_]: Timer, A](dataRef: Ref[F, Data[A]], handleError: (Data[A], Throwable) => F[Unit])(
    implicit F: Sync[F]
  ): F[A] =
    for {
      data <- dataRef.get
      _ <- data.error.fold(F.unit)(handleError(data, _))
    } yield data.value

  private def errorHandler[F[_], A](threshold: PosInt, handle: (Data[A], Throwable) => F[Unit])(
    implicit F: Applicative[F]
  ): (Data[A], Throwable) => F[Unit] = { (data, error) =>
    if (data.errorCount >= threshold.value) handle(data, error)
    else F.unit
  }

  private def make[F[_]: Timer, A, B](
    getData: Data[A] => F[A],
    pollInterval: FiniteDuration,
    read: Ref[F, Data[A]] => F[A]
  )(create: (() => F[A], A => F[Unit]) => B)(implicit F: Concurrent[F], monoid: Monoid[A]): Resource[F, B] = {
    def update(dataRef: Ref[F, Data[A]])(a: A): F[Unit] =
      timeNow.flatMap(time => dataRef.update(_.copy(value = a, updated = time)))

    Resource[F, B](for {
      initialTime <- timeNow
      data <- getData(Data(monoid.empty, initialTime))
      updatedTime <- timeNow
      dataRef <- Ref.of(Data(data, updatedTime))
      p <- poller(getData, pollInterval, dataRef)
    } yield create(() => read(dataRef), update(dataRef)) -> F.suspend(p.cancel))
  }

  def apply[F[_]: Timer, A: Monoid, B](getData: Data[A] => F[A], pollInterval: FiniteDuration, errorThreshold: PosInt)(
    create: (() => F[A], A => F[Unit]) => B
  )(implicit F: Concurrent[F]): Resource[F, B] =
    make[F, A, B](
      getData,
      pollInterval,
      dataRef => reader(dataRef, errorHandler(errorThreshold, (_, th) => F.raiseError[Unit](th)))
    )(create)

  def apply[F[_]: Timer, A: Monoid, B](
    getData: Data[A] => F[A],
    pollInterval: FiniteDuration,
    errorThreshold: PosInt,
    handleError: (Data[A], Throwable) => F[Unit]
  )(create: (() => F[A], A => F[Unit]) => B)(implicit F: Concurrent[F]): Resource[F, B] =
    make[F, A, B](getData, pollInterval, dataRef => reader(dataRef, errorHandler(errorThreshold, handleError)))(create)
}
