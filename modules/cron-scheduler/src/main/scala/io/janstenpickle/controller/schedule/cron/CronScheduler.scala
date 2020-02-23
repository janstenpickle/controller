package io.janstenpickle.controller.schedule.cron

import java.time.DayOfWeek

import cats.data.NonEmptyList
import cats.effect.{Concurrent, Resource, Timer}
import cats.effect.syntax.concurrent._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cron4s.Cron
import eu.timepit.fs2cron.awakeEveryCron
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.chrisdavenport.fuuid.FUUID
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.schedule.Scheduler
import io.janstenpickle.controller.schedule.model.Schedule
import natchez.Trace

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

object CronScheduler {
  def apply[F[_], G[_]](macros: Macro[F], store: WritableConfigSource[F, String, Schedule])(
    implicit F: Concurrent[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, Scheduler[F]] =
    Resource
      .liftF(for {
        logger <- Slf4jLogger.create[F]
        signal <- SignallingRef[F, Boolean](false)
      } yield (logger, signal))
      .flatMap {
        case (logger, signal) =>
          def stream: Stream[F, NonEmptyList[Unit]] =
            Stream
              .eval(store.getConfig.map(_.values.toSeq))
              .flatMap { schedules =>
                if (schedules.isEmpty)
                  Stream.awakeDelay[F](1.minute).flatMap(_ => Stream.emits[F, (String, Schedule)](schedules))
                else Stream.emits[F, (String, Schedule)](schedules)
              }
              .evalMap {
                case (id, schedule) =>
                  val days: SortedSet[DayOfWeek] = schedule.time.days.toSortedSet

                  F.fromEither(
                      Cron
                        .parse(s"0 ${schedule.time.minuteOfHour} ${schedule.time.hourOfDay} ? * ${days
                          .map(_.toString.toLowerCase().take(3))
                          .mkString(",")} *")
                    )
                    .flatMap { cron =>
                      logger
                        .info(s"Setting up '$id' schedule '${cron.show}' with action ${schedule.commands}")
                        .as(
                          awakeEveryCron(cron) >> Stream.eval(logger.info(s"Running schedule '$id'")) >> Stream
                            .eval(liftLower.lift(liftLower.lower("run.schedule") {
                              trace.put("id" -> id) >> schedule.commands.traverse(macros.executeCommand)
                            }))
                        )
                    }

              }
              .parJoinUnbounded
              .interruptWhen(signal)
              .onComplete(stream)
              .handleErrorWith(th => Stream.eval(logger.error(th)("Cron Scheduler failed")) >> stream)

          stream.compile.drain.background
            .map { _ =>
              new Scheduler[F] {
                override def create(schedule: Schedule): F[Option[String]] = trace.span("create.schedule") {
                  FUUID.randomFUUID.flatMap { uuid =>
                    val id = uuid.show
                    trace.put("id" -> id) >> store.upsert(id, schedule) >> signal
                      .set(true) >> signal.set(false).as(Some(id))
                  }
                }

                override def update(id: String, schedule: Schedule): F[Option[Unit]] = trace.span("update.schedule") {
                  trace.put("id" -> id) >> store.getValue(id).flatMap {
                    case None => F.pure(None)
                    case Some(_) => store.upsert(id, schedule) >> signal.set(true) >> signal.set(false).as(Some(()))
                  }
                }

                override def delete(id: String): F[Option[Unit]] = trace.span("delete.schedule") {
                  trace.put("id" -> id) >> store.getValue(id).flatMap {
                    case None => F.pure(None)
                    case Some(_) => store.deleteItem(id) >> signal.set(true) >> signal.set(false).as(Some(()))
                  }
                }

                override def info(id: String): F[Option[Schedule]] = trace.span("info.schedule") {
                  trace.put("id" -> id) >> store.getValue(id)
                }

                override def list: F[List[String]] = trace.span("list.schedule") {
                  store.listKeys.map(_.toList).flatTap { schedules =>
                    trace.put("schedule.count" -> schedules.size)
                  }
                }
              }
            }
      }
}
