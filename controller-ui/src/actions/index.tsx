import * as constants from '../constants';
import { RemoteData, RemoteButtons, ActivityData } from '../types/index';
import { activitiesAPI } from '../api/activities';
import { activitiesWs } from '../ws/activities';
import { buttonsAPI } from '../api/buttons';
import { buttonsWs } from '../ws/buttons';
import { remotesAPI } from '../api/remotes';
import { remotesWs } from '../ws/remotes';
import { roomsAPI } from '../api/rooms';
import { roomsWs } from '../ws/rooms';
import { Dispatch } from 'redux';
import { TSMap } from 'typescript-map';
import { Action } from 'redux';

export interface FocusRemote extends Action<constants.FOCUS_REMOTE> {
    type: constants.FOCUS_REMOTE;
    name: string;
}

export interface ToggleRemote extends Action<constants.TOGGLE_REMOTE> {
    type: constants.TOGGLE_REMOTE;
    name: string;
    value: boolean;
}

export interface SetActivity extends Action<constants.SET_ACTIVITY> {
    type: constants.SET_ACTIVITY;
    room: string;
    name: string;
}

export interface SetRoom extends Action<constants.SET_ROOM> {
    type: constants.SET_ROOM;
    room: string;
}

export interface ShowAll extends Action<constants.TOGGLE_SHOW_ALL> {
    type: constants.TOGGLE_SHOW_ALL;
}

export interface AddRemote extends Action<constants.ADD_REMOTE> {
    type: constants.ADD_REMOTE;
    remote: RemoteData
}

export interface EditMode extends Action<constants.EDIT_MODE> {
  type: constants.EDIT_MODE;
  enabled: boolean;
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

const loadButtonsWsAction = (dispatch: Dispatch<ControllerAction>) => buttonsWs((buttons: RemoteButtons[]) => dispatch(updateButtons(buttons)));

const loadButtonsApiAction = (dispatch: Dispatch<ControllerAction>) => buttonsAPI.fetchButtonsAsync().then((buttons: RemoteButtons[]) => dispatch(updateButtons(buttons)))

export const loadButtonsAction = () => (dispatch: Dispatch<ControllerAction>) => {
  loadButtonsWsAction(dispatch)
  loadButtonsApiAction(dispatch)
}

export interface LoadedButtons {
  type: constants.LOADED_BUTTONS;
  payload: RemoteButtons[];
}

export interface UpdatePlugState {
  type: constants.UPDATE_PLUG_STATE;
  plug?: string;
  state: boolean
}


const loadActivitiesWsAction = (dispatch: Dispatch<ControllerAction>) => activitiesWs((activities: TSMap<string, ActivityData>) => {
  dispatch(updateActivities(activities))
});


const loadActivitiesApiAction = (dispatch: Dispatch<ControllerAction>) => {
  activitiesAPI.fetchActivitiesAsync().then((activities: TSMap<string, ActivityData>) => {
    dispatch(updateActivities(activities))
  });
};

export const loadActivitiesAction = () => (dispatch: Dispatch<ControllerAction>) => {
  loadActivitiesWsAction(dispatch)
  loadActivitiesApiAction(dispatch)
}

export interface LoadedActivities {
  type: constants.LOADED_ACTIVITIES;
  payload: TSMap<string, ActivityData>;
}

const loadRemotesWsAction = (dispatch: Dispatch<ControllerAction>) => remotesWs((remotes: TSMap<string, RemoteData>) => dispatch(updateRemotes(remotes)));
const loadRemotesApiAction =  (dispatch: Dispatch<ControllerAction>) => remotesAPI.fetchRemotesAsync().then((remotes: TSMap<string, RemoteData>) => {dispatch(updateRemotes(remotes))});

export const loadRemotesAction = () => (dispatch: Dispatch<ControllerAction>) => {
  loadRemotesWsAction(dispatch)
  loadRemotesApiAction(dispatch)
}

export interface LoadedRooms {
  type: constants.LOADED_ROOMS;
  payload: string[]
}

const loadRoomsWsAction = (dispatch: Dispatch<ControllerAction>) => roomsWs((rooms: string[]) => dispatch(updateRooms(rooms)));
const loadRoomsApiAction =  (dispatch: Dispatch<ControllerAction>) => roomsAPI.fetchRoomsAsync().then((rooms: string[]) => dispatch(updateRooms(rooms)));

export const loadRoomsAction = () => (dispatch: Dispatch<ControllerAction>) => {
  loadRoomsWsAction(dispatch)
  loadRoomsApiAction(dispatch)
}

export interface LoadedRemotes {
  type: constants.LOADED_REMOTES;
  payload: TSMap<string, RemoteData>;
}

export type ControllerAction = FocusRemote | ToggleRemote | LoadedButtons | LoadedRemotes | LoadedActivities | LoadedRooms | SetActivity | SetRoom | ShowAll | UpdatePlugState | AddRemote | EditMode;

export function setActivity(room: string, name: string): SetActivity {
  return {
    type: constants.SET_ACTIVITY,
    room,
    name
  };
}

export function setRoom(room: string): SetRoom {
  return {
    type: constants.SET_ROOM,
    room
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

export function updateActivities(activities: TSMap<string, ActivityData>): LoadedActivities {
  return {
    type: constants.LOADED_ACTIVITIES,
    payload: activities
  };
}

export function updateRemotes(remotes: TSMap<string, RemoteData>): LoadedRemotes {
  return {
    type: constants.LOADED_REMOTES,
    payload: remotes
  };
}

export function updateRooms(rooms: string[]): LoadedRooms {
  return {
    type: constants.LOADED_ROOMS,
    payload: rooms
  }
}

export function updatePlugState(state:boolean, plug?: string): UpdatePlugState {
  return {
    type: constants.UPDATE_PLUG_STATE,
    plug: plug,
    state: state
  }
}

export function addRemote(remote: RemoteData): AddRemote {
  return {
    type: constants.ADD_REMOTE,
    remote
  }
}

export function editMode(enabled: boolean): EditMode {
  return {
    type: constants.EDIT_MODE,
    enabled
  }
}