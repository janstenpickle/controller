package io.janstenpickle.controller.poller

import cats.effect._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Applicative, Apply, Eq, Functor}
import eu.timepit.refined.types.numeric.PosInt
import fs2.Stream
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanStatus}
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.FiniteDuration

object DataPoller {
  case class Data[A](value: A, updated: Long, errorCount: Int = 0, error: Option[Throwable] = None)

  private def timeNow[F[_]: Functor](implicit clock: Clock[F]): F[Long] = clock.realTime.map(_.toMillis)

  private def poller[F[_], A: Eq](
    getData: Data[A] => F[A],
    pollInterval: FiniteDuration,
    dataRef: Ref[F, Data[A]],
    onUpdate: (A, A) => F[Unit]
  )(implicit F: Async[F], logger: Logger[F], trace: Trace[F]) = {

    def update(now: Long): F[Unit] =
      trace
        .span("poller.update") {
          for {
            current <- dataRef.get
            data <- getData(current)
            _ <- dataRef.set(Data(data, now))
            updated = current.value.neqv(data)
            _ <- trace.put("updated", updated)
            _ <- if (updated) onUpdate(current.value, data) else F.unit
          } yield ()
        }
        .handleErrorWith(
          th =>
            trace
              .span("poller.error") {
                trace.setStatus(SpanStatus.Internal(th.getMessage)) *>
                  logger.warn(th)(s"Failed to update polled data") *> dataRef
                  .tryUpdate(d => d.copy(errorCount = d.errorCount + 1, error = Some(th))) *> dataRef.get
                  .flatMap(d => trace.putAll("error.count" -> d.errorCount))
              }
              .handleError(_ => ())
        )

    def stream: Stream[F, Unit] =
      Stream.fixedRate(pollInterval).evalMap(_ => timeNow.flatMap(update)).onComplete(stream).handleErrorWith { th =>
        Stream.eval(logger.error(th)("Poll stream encountered an error, restarting")) >> stream
      }

    F.start(stream.compile.drain)
  }

  private def reader[F[_], A](
    dataRef: Ref[F, Data[A]],
    handleError: (Data[A], Throwable) => F[A]
  )(implicit F: Sync[F], trace: Trace[F]): F[A] =
    trace.span("poller.read.data") {
      for {
        data <- dataRef.get
        errorHandled <- data.error.fold(F.pure(data))(handleError(data, _).map(a => data.copy(value = a)))
      } yield errorHandled.value
    }

  private def errorHandler[F[_], A](
    threshold: PosInt,
    handle: (Data[A], Throwable) => F[A]
  )(implicit F: Applicative[F], trace: Trace[F]): (Data[A], Throwable) => F[A] = { (data, error) =>
    trace.span("poller.handle.error") {
      trace.putAll("error.count" -> data.errorCount, "error.threshold" -> threshold.value) *> {
        if (data.errorCount >= threshold.value) trace.put("error", true) *> handle(data, error)
        else F.pure(data.value)
      }
    }
  }

  private def make[F[_], A: Eq, B](
    getData: Data[A] => F[A],
    pollInterval: FiniteDuration,
    read: Ref[F, Data[A]] => F[A],
    onUpdate: (A, A) => F[Unit]
  )(
    create: (() => F[A], A => F[Unit]) => B
  )(implicit F: Async[F], empty: Empty[A], logger: Logger[F], trace: Trace[F]): Resource[F, B] = {
    def update(dataRef: Ref[F, Data[A]])(a: A): F[Unit] = trace.span("update.poller.state") {
      for {
        current <- dataRef.get
        time <- timeNow
        _ <- dataRef.update(_.copy(value = a, updated = time))
        _ <- if (current.value.neqv(a)) onUpdate(current.value, a) else F.unit
      } yield ()
    }

    Resource[F, B](trace.span("poller.start") {
      for {
        _ <- logger.info("Starting initialising poller")
        initialTime <- timeNow
        data <- getData(Data(empty.empty, initialTime)).handleError(_ => empty.empty)
        updatedTime <- timeNow
        dataRef <- Ref.of(Data(data, updatedTime))
        _ <- if (empty.empty.neqv(data)) onUpdate(empty.empty, data)
        else F.unit
        p <- poller(getData, pollInterval, dataRef, onUpdate)
        _ <- logger.info("Finished initialising poller")
      } yield
        create(() => read(dataRef), update(dataRef)) -> trace.span("poller.stop") {
          F.defer(logger.info("Stopping poller") *> p.cancel)
        }
    })
  }

  def traced[F[_], G[_], A, B](
    name: String,
    fields: (String, AttributeValue)*
  ): TracedPollerPartiallyApplied[F, G, A, B] =
    new TracedPollerPartiallyApplied[F, G, A, B] {

      private def span[C](n: String)(k: F[C])(implicit F: Apply[F], trace: Trace[F]): F[C] = trace.span(n) {
        trace.putAll(fields ++ List("poller.name" -> StringValue(name)): _*) *> k
      }

      override def apply(
        getData: Data[A] => F[A],
        pollInterval: FiniteDuration,
        errorThreshold: PosInt,
        onUpdate: (A, A) => F[Unit],
        k: ResourceKleisli[G, SpanName, Span[G]]
      )(create: (() => F[A], A => F[Unit]) => B)(
        implicit F: Sync[F],
        trace: Trace[F],
        G: Async[G],
        empty: Empty[A],
        eq: Eq[A],
        logger: Logger[F],
        provide: Provide[G, F, Span[G]],
      ): Resource[F, B] = {
        def low[AA](fa: F[AA]): G[AA] = k.run(s"$name.poll").use(provide.provide(fa))

        def noop: F ~> G = new (F ~> G) {
          override def apply[AA](fa: F[AA]): G[AA] = Span.noop[G].use(provide.provide(fa))
        }

        implicit val l: Logger[G] = logger.mapK(noop)

        DataPoller[G, A, B](getData.andThen { read =>
          low(span("poll")(read))
        }, pollInterval, errorThreshold, (o: A, n: A) => low(onUpdate(o, n))) { (get, update) =>
          create(
            () => span("poller.read.state") { provide.lift(get()) },
            a => span("poller.update.state") { provide.lift(update(a)) }
          )
        }.mapK(provide.liftK)
      }

      override def apply(
        getData: Data[A] => F[A],
        pollInterval: FiniteDuration,
        errorThreshold: PosInt,
        handleError: (Data[A], Throwable) => F[A],
        onUpdate: (A, A) => F[Unit],
        k: ResourceKleisli[G, SpanName, Span[G]]
      )(create: (() => F[A], A => F[Unit]) => B)(
        implicit F: Sync[F],
        trace: Trace[F],
        G: Async[G],
        empty: Empty[A],
        eq: Eq[A],
        logger: Logger[F],
        provide: Provide[G, F, Span[G]],
      ): Resource[F, B] = {
        def low[AA](fa: F[AA]): G[AA] = k.run(s"$name.poll").use(provide.provide(fa))

        def noop: F ~> G = new (F ~> G) {
          override def apply[AA](fa: F[AA]): G[AA] = Span.noop[G].use(provide.provide(fa))
        }

        implicit val l: Logger[G] = logger.mapK(noop)

        DataPoller[G, A, B](
          getData.andThen { read =>
            low(span("poll")(read))
          },
          pollInterval,
          errorThreshold,
          (data: Data[A], th: Throwable) => low(span("handle.error")(handleError(data, th))),
          (o: A, n: A) => low(onUpdate(o, n))
        ) { (get, update) =>
          create(
            () =>
              span("read.state") {
                provide.lift(get())
            },
            a =>
              span("update.state") {
                provide.lift(update(a))
            }
          )
        }
      }.mapK(provide.liftK)
    }

  trait TracedPollerPartiallyApplied[F[_], G[_], A, B] {
    def apply(
      getData: Data[A] => F[A],
      pollInterval: FiniteDuration,
      errorThreshold: PosInt,
      onUpdate: (A, A) => F[Unit],
      k: ResourceKleisli[G, SpanName, Span[G]]
    )(create: (() => F[A], A => F[Unit]) => B)(
      implicit F: Sync[F],
      trace: Trace[F],
      G: Async[G],
      empty: Empty[A],
      eq: Eq[A],
      logger: Logger[F],
      provide: Provide[G, F, Span[G]],
    ): Resource[F, B]

    def apply(
      getData: Data[A] => F[A],
      pollInterval: FiniteDuration,
      errorThreshold: PosInt,
      handleError: (Data[A], Throwable) => F[A],
      onUpdate: (A, A) => F[Unit],
      k: ResourceKleisli[G, SpanName, Span[G]]
    )(create: (() => F[A], A => F[Unit]) => B)(
      implicit F: Sync[F],
      trace: Trace[F],
      G: Async[G],
      empty: Empty[A],
      eq: Eq[A],
      logger: Logger[F],
      provide: Provide[G, F, Span[G]],
    ): Resource[F, B]
  }

  def apply[F[_], A: Empty: Eq, B](
    getData: Data[A] => F[A],
    pollInterval: FiniteDuration,
    errorThreshold: PosInt,
    onUpdate: (A, A) => F[Unit]
  )(create: (() => F[A], A => F[Unit]) => B)(implicit F: Async[F], logger: Logger[F]): Resource[F, B] = {
    import Trace.Implicits.noop

    make[F, A, B](
      getData,
      pollInterval,
      dataRef =>
        reader(
          dataRef,
          errorHandler(
            errorThreshold,
            (_, th) => logger.error(th)("Poller exceeded failure threshold") *> F.raiseError[A](th)
          )
      ),
      onUpdate
    )(create)
  }

  def apply[F[_]: Async: Logger, A: Empty: Eq, B](
    getData: Data[A] => F[A],
    pollInterval: FiniteDuration,
    errorThreshold: PosInt,
    handleError: (Data[A], Throwable) => F[A],
    onUpdate: (A, A) => F[Unit]
  )(create: (() => F[A], A => F[Unit]) => B)(implicit F: Concurrent[F]): Resource[F, B] = {
    import Trace.Implicits.noop

    make[F, A, B](
      getData,
      pollInterval,
      dataRef => reader(dataRef, errorHandler(errorThreshold, handleError)),
      onUpdate
    )(create)
  }
}
