import { ControllerAction, setActivity, setRoom } from '../actions';
import { toTitleCase } from '../components/Rooms'
import { StoreState, RemoteButtons, RemoteData, ActivityData } from '../types/index';
import { FOCUS_REMOTE, TOGGLE_REMOTE, LOADED_BUTTONS, LOADED_REMOTES, LOADED_ACTIVITIES, LOADED_ROOMS, SET_ACTIVITY, TOGGLE_SHOW_ALL, UPDATE_PLUG_STATE, SET_ROOM, ADD_REMOTE, EDIT_MODE } from '../constants/index';
import { TSMap } from "typescript-map";

const isActive = (currentRoom: string, currentActivity: TSMap<string, string>, remote: RemoteData) => {
  const room: (() => string) = () => {
    if (remote.rooms.length === 0) {
      return currentRoom;
    } else {
      return remote.rooms.find((room: string) => room === currentRoom) || '';
    }
  }

  const activity = currentActivity.get(room()) || ''

  if (remote.activities.length === 0 && currentRoom === room()) {
    return true;
  } else {
    return remote.activities.includes(activity)
  }
}

export const initialState: StoreState = {
  buttons: [],
  activities: new TSMap<string, ActivityData>(),
  remotes: new TSMap<string, RemoteData>(),
  focusedRemote: 'TV',
  currentActivity: new TSMap<string, string>(),
  currentRoom: localStorage.getItem('room') || '',
  showAll: !(document.documentElement.clientWidth < 900),
  editMode: false,
  rooms: []
}

export function controller (state: StoreState = initialState, action: ControllerAction): StoreState {
  switch (action.type) {
    case EDIT_MODE:
      return { ...state, editMode: action.enabled }
    case ADD_REMOTE:
      action.remote.isActive = isActive(state.currentRoom, state.currentActivity, action.remote)

      const newRemotes = state.remotes.clone()
      newRemotes.set(action.remote.name, action.remote)

      return { ...state, remotes: newRemotes }
    case FOCUS_REMOTE:
      return { ...state, focusedRemote: action.name };
    case TOGGLE_REMOTE:
      const remote = state.remotes.get(action.name);
      remote.isActive = action.value;
      const remotes = state.remotes.set(action.name, remote);
      return { ...state, remotes: remotes.clone() };
    case LOADED_BUTTONS:
      return { ...state, buttons: action.payload };
    case LOADED_REMOTES: {
      const rs = action.payload.clone()
      rs.forEach((value, key, index) =>
          value.isActive = isActive(state.currentRoom, state.currentActivity, value)
      ) 
      return { ...state, remotes: rs }
    }
    case LOADED_ACTIVITIES:
      const current = new TSMap<string, string>(
        action.payload.clone()
          .filter(activity => (activity.isActive || false))
          .map(activity => [activity.room || '', activity.name])
      )

      if (state.currentActivity !== current) {
        const activity = current.get(state.currentRoom) || ''
        return controller({ ...state, activities: action.payload.clone(), currentActivity: current },  setActivity(state.currentRoom, activity))
      } else {
        return { ...state, activities: action.payload.clone() };
      }
    case LOADED_ROOMS:
      const currentRoom = () => { 
        if (state.currentRoom === '') {
            return action.payload[0] || ''
        } else {
          return state.currentRoom
        }
      }
      const room = currentRoom()
      return controller({ ...state, rooms: action.payload, currentRoom: room }, setRoom(room))
    case SET_ROOM:
      document.title = toTitleCase(action.room);
      localStorage.setItem('room', action.room)
      return controller({ ...state, currentRoom: action.room }, setActivity(action.room, state.currentActivity.get(action.room) || ''))
    case TOGGLE_SHOW_ALL:
      return { ...state, showAll: !state.showAll };
    case SET_ACTIVITY: {
      const newActivity = state.currentActivity.set(action.room, action.name)
      const rs = state.remotes.clone()
      rs.forEach((value, key, index) =>
          value.isActive = isActive(action.room, newActivity, value)
      )
      return { ...state, remotes: rs, currentActivity: newActivity }
    }
    case UPDATE_PLUG_STATE:
      const buttons = state.buttons.map ((button: RemoteButtons) => {
          if (button.name === action.plug) {
            switch (button.tag) {
              case "macro": 
                button.isOn = action.state;
                return button;
              case "switch":
                button.isOn = action.state;
                return button;
               default:
                return button; 
            }
          } else {
            return(button);
          }
        }
      )
      return { ...state, buttons: buttons }
    default:
      console.log("unmatched action " + action)
      return state;
  }
}

