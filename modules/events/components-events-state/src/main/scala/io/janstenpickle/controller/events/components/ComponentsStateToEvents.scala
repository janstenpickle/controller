package io.janstenpickle.controller.events.components

import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, Apply, Functor, Monad}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.model
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, MacroEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.model.{Button, Command, Remote}
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.switch.SwitchProvider

object ComponentsStateToEvents {
  def remoteControls[F[_]: Functor](remoteControls: RemoteControls[F], source: String): F[List[RemoteEvent]] =
    remoteControls.listCommands.map { commands =>
      val remotes = commands
        .map[RemoteEvent] { command =>
          RemoteEvent.RemoteAddedEvent(command.remote, source)
        }
        .distinct

      remotes ++ commands.map[RemoteEvent] { command =>
        RemoteEvent.RemoteLearntCommand(command.remote, command.device, command.source, command.name)
      }
    }

  def switches[F[_]: Monad](switches: SwitchProvider[F]): F[List[SwitchEvent]] =
    switches.getSwitches
      .flatMap(_.toList.flatTraverse {
        case (key, switch) =>
          switch.getState.map { state =>
            List(SwitchEvent.SwitchAddedEvent(key, switch.metadata), SwitchEvent.SwitchStateUpdateEvent(key, state))
          }
      })

  def activities[F[_]: Functor](
    activities: ConfigSource[F, String, model.Activity],
    source: String
  ): F[List[ConfigEvent]] =
    activities.getConfig.map(_.values.values.toList.map { activity =>
      ConfigEvent.ActivityAddedEvent(activity, source)
    })

  def remotes[F[_]: Functor](remotes: ConfigSource[F, NonEmptyString, Remote], source: String): F[List[ConfigEvent]] =
    remotes.getConfig.map(_.values.values.toList.map { remote =>
      ConfigEvent.RemoteAddedEvent(remote, source)
    })

  def macros[F[_]: Functor](macros: ConfigSource[F, NonEmptyString, NonEmptyList[Command]]): F[List[MacroEvent]] =
    macros.getConfig.map(_.values.toList.map { case (key, commands) => MacroEvent.StoredMacroEvent(key, commands) })

  def macroConfig[F[_]: Functor](
    macros: ConfigSource[F, NonEmptyString, NonEmptyList[Command]],
    source: String
  ): F[List[ConfigEvent]] =
    macros.getConfig.map(_.values.toList.map {
      case (key, commands) => ConfigEvent.MacroAddedEvent(key, commands, source)
    })

  def buttons[F[_]: Functor](buttons: ConfigSource[F, String, Button], source: String): F[List[ConfigEvent]] =
    buttons.getConfig.map(_.values.values.toList.map { button =>
      ConfigEvent.ButtonAddedEvent(button, source)
    })

  def discovery[F[_]: Applicative](rename: DeviceRename[F]): F[List[DeviceDiscoveryEvent]] =
    Apply[F].map2(rename.assigned.map(_.toList.map {
      case (key, value) => DeviceDiscoveryEvent.DeviceDiscovered(key, value)
    }), rename.unassigned.map(_.toList.map {
      case (key, meta) => DeviceDiscoveryEvent.UnmappedDiscovered(key, meta)
    }))(_ ++ _)

}
