import { TSMap } from "typescript-map";

export interface StoreState {
    buttons: RemoteButtons[];
    activities: ActivityButton[];
    remotes: TSMap<string, RemoteData>;
    currentActivity: string;
    focusedRemote: string;
    showAll: boolean;
}

export interface Coords {
  x: number;
  y: number;
}

export interface RemoteData {
  name: string;
  activities: string[];
  isActive: boolean;
  buttons: RemoteButtons[];
  lg?: Coords;
  md?: Coords;
  sm?: Coords;
  xs?: Coords;
  xxs?: Coords;
}

export type RemoteButtons = RemoteButtonIcon | RemoteButtonLabel | SwitchButtonIcon | SwitchButtonLabel | MacroButtonIcon | MacroButtonLabel | ContextButtonIcon | ContextButtonLabel

export interface ControlButton {
  name: string;
  newRow?: boolean;
  colored?: boolean;
}

export interface ActivityButton extends ControlButton {
  tag: "activity"
  name: string;
  label: string;
  isActive?: boolean;
}

export interface RemoteButton extends ControlButton {
  tag: "remote"
  remote: string;
  device: string;
}

export interface RemoteButtonIcon extends RemoteButton {
  renderTag: "icon"
  icon: string;
}

export interface RemoteButtonLabel extends RemoteButton {
  renderTag: "label"
  label: string;
}

export interface SwitchButton extends ControlButton {
  tag: "switch"
  device: string;
  isOn: boolean;
}

export interface SwitchButtonIcon extends SwitchButton {
  renderTag: "icon"
  icon: string;
}

export interface SwitchButtonLabel extends SwitchButton {
  renderTag: "label"
  label: string;
}

export interface MacroButton extends ControlButton {
  tag: "macro"
  isOn?: boolean;
}

export interface MacroButtonIcon extends MacroButton {
  renderTag: "icon"
  icon: string;
}

export interface MacroButtonLabel extends MacroButton {
  renderTag: "label"
  label: string;
}

export interface ContextButton extends ControlButton {
  tag: "context"
}

export interface ContextButtonIcon extends ContextButton {
  renderTag: "icon"
  icon: string;
}

export interface ContextButtonLabel extends ContextButton {
  renderTag: "label"
  label: string
}