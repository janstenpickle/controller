package io.janstenpickle.controller.poller

import java.util.concurrent.TimeUnit

import cats.Monoid
import cats.effect.concurrent.Ref
import cats.effect._

import scala.concurrent.duration.FiniteDuration
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import eu.timepit.refined.types.numeric.PosInt

object DataPoller {
  case class Data[A](value: A, updated: Long, errorCount: Int = 0, error: Option[Throwable] = None)

  def timeNow[F[_]](implicit timer: Timer[F]): F[Long] = timer.clock.realTime(TimeUnit.MILLISECONDS)

  private def poller[F[_], A](getData: Data[A] => F[A], pollInterval: FiniteDuration, dataRef: Ref[F, Data[A]])(
    implicit F: Concurrent[F],
    timer: Timer[F]
  ): F[Fiber[F, Unit]] = {

    def update(now: Long): F[Unit] =
      (for {
        current <- dataRef.get
        data <- getData(current)
        _ <- dataRef.set(Data(data, now))
      } yield ()).handleErrorWith(
        th => dataRef.tryUpdate(d => d.copy(updated = now, errorCount = d.errorCount + 1, error = Some(th))).void
      )

    def loop(now: Long): F[Unit] = (update(now) *> timer.sleep(pollInterval)) *> timeNow.flatMap(loop)

    F.start(timeNow.flatMap(loop))
  }

  private def reader[F[_], A](
    dataRef: Ref[F, Data[A]],
    handleError: (Int, Throwable) => F[Unit],
    handleStaleData: Data[A] => F[Unit],
    maxAge: FiniteDuration,
  )(implicit F: Sync[F], timer: Timer[F]): F[A] =
    for {
      data <- dataRef.get
      now <- timeNow
      _ <- if ((data.updated + maxAge.toMillis) < now) handleStaleData(data) else F.unit
      _ <- data.error.fold(F.unit)(handleError(data.errorCount, _))
    } yield data.value

  private def reader[F[_], A](
    dataRef: Ref[F, Data[A]],
    handleError: (Int, Throwable) => F[Unit]
  )(implicit F: Sync[F], timer: Timer[F]): F[A] =
    for {
      data <- dataRef.get
      _ <- data.error.fold(F.unit)(handleError(data.errorCount, _))
    } yield data.value

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

  def apply[F[_]: Timer, A: Monoid, B](getData: Data[A] => F[A], pollInterval: FiniteDuration)(
    create: (() => F[A], A => F[Unit]) => B
  )(implicit F: Concurrent[F]): Resource[F, B] =
    make[F, A, B](getData, pollInterval, dataRef => reader(dataRef, { case (_, th) => F.raiseError[Unit](th) }))(create)

  def apply[F[_]: Timer, A: Monoid, B](
    getData: Data[A] => F[A],
    pollInterval: FiniteDuration,
    handelError: Throwable => F[Unit]
  )(create: (() => F[A], A => F[Unit]) => B)(implicit F: Concurrent[F]): Resource[F, B] =
    make[F, A, B](getData, pollInterval, dataRef => reader(dataRef, handelError))(create)

  def apply[F[_]: Timer, A: Monoid, B](
    getData: Data[A] => F[A],
    pollInterval: FiniteDuration,
    handleStaleData: Data[A] => F[Unit],
    missedPolls: PosInt
  )(create: (() => F[A], A => F[Unit]) => B)(implicit F: Concurrent[F]): Resource[F, B] =
    make[F, A, B](
      getData,
      pollInterval,
      dataRef => reader[F, A](dataRef, F.raiseError[Unit](_), handleStaleData, pollInterval * missedPolls.value.toLong)
    )(create)

  def apply[F[_]: Timer, A: Monoid, B](
    getData: Data[A] => F[A],
    pollInterval: FiniteDuration,
    handelError: Throwable => F[Unit],
    handleStaleData: Data[A] => F[Unit],
    missedPolls: PosInt
  )(create: (() => F[A], A => F[Unit]) => B)(implicit F: Concurrent[F]): Resource[F, B] =
    make[F, A, B](
      getData,
      pollInterval,
      dataRef => reader[F, A](dataRef, handelError, handleStaleData, pollInterval * missedPolls.value.toLong)
    )(create)
}
