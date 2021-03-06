import { TSMap } from "typescript-map";

export interface StoreState {
  currentRoom: string;
  rooms: string[];
  buttons: RemoteButtons[];
  activities: TSMap<string, ActivityData>;
  remotes: TSMap<string, RemoteData>;
  currentActivity: TSMap<string, string>;
  focusedRemote: string;
  showAll: boolean;
  editMode: boolean;
}

export interface Switch {
  device: string;
  name: string;
}

export interface RemoteCommandSource {
  name: string;
  type: string;
}

export interface RemoteCommand {
  remote: string;
  source?: RemoteCommandSource;
  device: string;
  name: string;
}

export interface RemoteData {
  name: string;
  label: string;
  rooms: string[];
  activities: string[];
  isActive: boolean;
  buttons: RemoteButtons[];
  order?: number;
  editable: boolean;
}

export interface ActivityData {
  name: string;
  label: string;
  room: string;
  isActive?: boolean;
  order?: number;
  contextButtons: ContextButtons[];
  editable: boolean;
}

export type ContextButtons =
  | ContextButtonMappingRemote
  | ContextButtonMappingMacro;

export interface ContextButtonMapping {
  name: string;
}

export interface ContextButtonMappingRemote extends ContextButtonMapping {
  tag: "remote";
  remote: string;
  commandSource?: RemoteCommandSource;
  device: string;
  command: string;
}

export interface ContextButtonMappingMacro extends ContextButtonMapping {
  tag: "macro";
  macro: string;
}

export type RemoteButtons =
  | RemoteButtonIcon
  | RemoteButtonLabel
  | SwitchButtonIcon
  | SwitchButtonLabel
  | MacroButtonIcon
  | MacroButtonLabel
  | ContextButtonIcon
  | ContextButtonLabel;

export interface ControlButton {
  name: string;
  newRow?: boolean;
  colored?: boolean;
  color?: string;
  room?: string;
  type?: string;
  order?: number;
}

export interface ActivityButton extends ControlButton {
  tag: "activity";
  name: string;
  label: string;
  isActive?: boolean;
}

export interface RemoteButton extends ControlButton {
  tag: "remote";
  remote: string;
  commandSource?: RemoteCommandSource;
  device: string;
}

export interface RemoteButtonIcon extends RemoteButton {
  renderTag: "icon";
  icon: string;
}

export interface RemoteButtonLabel extends RemoteButton {
  renderTag: "label";
  label: string;
}

export interface SwitchButton extends ControlButton {
  tag: "switch";
  device: string;
  isOn: boolean;
}

export interface SwitchButtonIcon extends SwitchButton {
  renderTag: "icon";
  icon: string;
}

export interface SwitchButtonLabel extends SwitchButton {
  renderTag: "label";
  label: string;
}

export interface MacroButton extends ControlButton {
  tag: "macro";
  isOn?: boolean;
}

export interface MacroButtonIcon extends MacroButton {
  renderTag: "icon";
  icon: string;
}

export interface MacroButtonLabel extends MacroButton {
  renderTag: "label";
  label: string;
}

export interface ContextButton extends ControlButton {
  tag: "context";
}

export interface ContextButtonIcon extends ContextButton {
  renderTag: "icon";
  icon: string;
}

export interface ContextButtonLabel extends ContextButton {
  renderTag: "label";
  label: string;
}

export type MacroCommand =
  | MacroSleep
  | MacroToggleSwitch
  | MacroSwitchOn
  | MacroSwitchOff
  | MacroRemote
  | MacroMacro;

export interface MacroSleep {
  type: "Sleep";
  millis: number;
}

export interface MacroToggleSwitch {
  type: "ToggleSwitch";
  device: string;
  name: string;
}

export interface MacroSwitchOn {
  type: "SwitchOn";
  device: string;
  name: string;
}

export interface MacroSwitchOff {
  type: "SwitchOff";
  device: string;
  name: string;
}

export interface MacroRemote {
  type: "Remote";
  remote: string;
  commandSource?: RemoteCommandSource;
  device: string;
  command: string;
}

export interface MacroMacro {
  type: "Macro";
  name: string;
}
