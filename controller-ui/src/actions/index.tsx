import * as constants from '../constants';
import { ActivityButton, RemoteData, RemoteButtons } from '../types/index';
import { activitiesAPI } from '../api/activities';
import { buttonsAPI } from '../api/buttons';
import { remotesAPI } from '../api/remotes';
import { Dispatch } from 'redux';
import { TSMap } from 'typescript-map';

export interface FocusRemote {
    type: constants.FOCUS_REMOTE;
    name: string;
}

export interface ToggleRemote {
    type: constants.TOGGLE_REMOTE;
    name: string;
    value: boolean;
}

export interface SetActivity {
    type: constants.SET_ACTIVITY;
    name: string;
}

export interface ShowAll {
    type: constants.TOGGLE_SHOW_ALL;
}

const createPoller = (interval: number, initialDelay: number) => {
  let timeoutId: number = 0;
  return (fn: any) => {
    window.clearTimeout(timeoutId);
    let poller = () => {
      timeoutId = window.setTimeout(poller, interval);
      return fn();
    };
    if (initialDelay) {
      return timeoutId = window.setTimeout(poller, interval);
    }
    return poller();
  };
};

export const createPollingAction = (action: any, interval: number, initialDelay: number) => {
  const poll = createPoller(interval, initialDelay);
  return () => (dispatch: Dispatch<ControllerAction>, getState: ControllerAction) => poll(() => action(dispatch, getState));
};

export const loadButtonsAction = createPollingAction((dispatch: Dispatch<ControllerAction>) => {
  buttonsAPI.fetchButtonsAsync().then((buttons: RemoteButtons[]) => {dispatch(updateButtons(buttons))});
}, 1500, 0);

export interface LoadedButtons {
  type: constants.LOADED_BUTTONS;
  payload: RemoteButtons[];
}

export interface UpdatePlugState {
  type: constants.UPDATE_PLUG_STATE;
  plug?: string;
  state: boolean
}

export const loadActivitiesAction = createPollingAction((dispatch: Dispatch<ControllerAction>) => {
  activitiesAPI.fetchActivitiesAsync().then((buttons: ActivityButton[]) => {
    const current = buttons.filter(button => button.isActive || false)[0].name || localStorage.getItem('activity') || ''
    dispatch(updateActivities(buttons, current))
  });
}, 3000, 0);

export interface LoadedActivities {
  type: constants.LOADED_ACTIVITIES;
  payload: ActivityButton[];
  current: string;
}

export const loadRemotesAction =  createPollingAction((dispatch: Dispatch<ControllerAction>) => {
  remotesAPI.fetchRemotesAsync().then((remotes: TSMap<string, RemoteData>) => {dispatch(updateRemotes(remotes))});
}, 1500, 0);

export interface LoadedRemotes {
  type: constants.LOADED_REMOTES;
  payload: TSMap<string, RemoteData>;
}

export type ControllerAction = FocusRemote | ToggleRemote | LoadedButtons | LoadedRemotes | LoadedActivities | SetActivity | ShowAll | UpdatePlugState;

export function setActivity(name: string): SetActivity {
  return {
    type: constants.SET_ACTIVITY,
    name
  };
}

export function focusRemote(name: string): FocusRemote {
  return {
    type: constants.FOCUS_REMOTE,
    name
  };
}

export function toggleShowAll(): ShowAll {
  return {
    type: constants.TOGGLE_SHOW_ALL
  };
}

export function toggleRemote(name: string, value: boolean): ToggleRemote {
  return {
    type: constants.TOGGLE_REMOTE,
    name,
    value
  };
}

export function updateButtons(buttons: RemoteButtons[]): LoadedButtons {
  return {
    type: constants.LOADED_BUTTONS,
    payload: buttons
  };
}

export function updateActivities(buttons: ActivityButton[], current: string): LoadedActivities {
  return {
    type: constants.LOADED_ACTIVITIES,
    payload: buttons,
    current: current
  };
}

export function updateRemotes(remotes: TSMap<string, RemoteData>): LoadedRemotes {
  return {
    type: constants.LOADED_REMOTES,
    payload: remotes
  };
}

export function updatePlugState(state:boolean, plug?: string): UpdatePlugState {
  return {
    type: constants.UPDATE_PLUG_STATE,
    plug: plug,
    state: state
  }
}