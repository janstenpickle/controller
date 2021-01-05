package io.janstenpickle.controller.tplink.schedule

import cats.data.NonEmptyList
import cats.instances.list._
import cats.instances.option._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.traverse._
import cats.{Monad, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Command.{SwitchOff, SwitchOn}
import io.janstenpickle.controller.model.{Command, State}
import io.janstenpickle.controller.schedule.model.Schedule
import io.janstenpickle.controller.schedule.{NamedScheduler, Scheduler}
import io.janstenpickle.controller.tplink.device.TplinkDevice
import io.janstenpickle.controller.tplink.{DeviceType, TplinkDiscovery}

object TplinkScheduler {
  def apply[F[_]: Parallel](discovery: TplinkDiscovery[F])(implicit F: Monad[F]): Scheduler[F] = new NamedScheduler[F] {
    override val name: String = "tplink"

    private def devices =
      discovery.devices.map(_.devices.toList.collect {
        case ((_, DeviceType.SmartPlug(_)), dev: TplinkDevice.SmartPlug[F]) => dev
      })

    private def fallback[A](f: TplinkDevice.SmartPlug[F] => F[Option[A]]): F[Option[A]] =
      devices.flatMap(F.tailRecM[List[TplinkDevice.SmartPlug[F]], Option[A]](_) {
        case h :: t =>
          f(h).map {
            case Some(a) => Right(Some(a))
            case None => Left(t)
          }
        case Nil => F.pure(Right(None))
      })

    private def doOn[A](device: NonEmptyString, name: NonEmptyString)(f: TplinkDevice.SmartPlug[F] => F[A]) =
      devices.flatMap(_.find(d => d.device == device && d.name == name).traverse(f))

    override def create(schedule: Schedule): F[Option[String]] =
      schedule.commands match {
        case NonEmptyList(_, _ :: _) => F.pure(None)
        case NonEmptyList(SwitchOn(device, name), Nil) => doOn(device, name)(_.scheduleAction(schedule.time, State.On))
        case NonEmptyList(SwitchOff(device, name), Nil) =>
          doOn(device, name)(_.scheduleAction(schedule.time, State.Off))
        case NonEmptyList(_, Nil) => F.pure(None)
      }

    override def update(id: String, schedule: Schedule): F[Option[Unit]] =
      schedule.commands match {
        case NonEmptyList(_, _ :: _) => delete(id).as(None)
        case NonEmptyList(SwitchOn(device, name), Nil) =>
          doOn(device, name)(_.updateAction(id, schedule.time, State.On))
        case NonEmptyList(SwitchOff(device, name), Nil) =>
          doOn(device, name)(_.updateAction(id, schedule.time, State.Off))
        case NonEmptyList(_, Nil) => delete(id).as(None)
      }

    override def delete(id: String): F[Option[Unit]] = fallback(_.deleteSchedule(id))

    override def info(id: String): F[Option[Schedule]] =
      fallback(_.scheduleInfo(id).map(_.map {
        case (device, name, time, State.On) => Schedule(time, NonEmptyList.of(Command.SwitchOn(device, name)))
        case (device, name, time, State.Off) => Schedule(time, NonEmptyList.of(Command.SwitchOff(device, name)))
      }))

    override def list: F[Set[(String, String)]] =
      devices.flatMap(_.parFlatTraverse(_.listSchedules)).map(_.map(name -> _).toSet)
  }
}
