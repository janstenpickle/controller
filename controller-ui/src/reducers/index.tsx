import { ControllerAction, setActivity, setRoom } from '../actions';
import { toTitleCase } from '../components/Rooms'
import { StoreState, RemoteButtons, RemoteData } from '../types/index';
import { FOCUS_REMOTE, TOGGLE_REMOTE, LOADED_BUTTONS, LOADED_REMOTES, LOADED_ACTIVITIES, LOADED_ROOMS, SET_ACTIVITY, TOGGLE_SHOW_ALL, UPDATE_PLUG_STATE, SET_ROOM, OPEN_DIALOG, CLOSE_DIALOG, ADD_REMOTE } from '../constants/index';
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

export function controller(state: StoreState, action: ControllerAction): StoreState {
  switch (action.type) {
    case OPEN_DIALOG:
      return { ...state, newRemoteDialogOpen: true };
    case CLOSE_DIALOG:
      return { ...state, newRemoteDialogOpen: false };
    case ADD_REMOTE:
      const newRemote: RemoteData = {
        name: action.remote,
        rooms: [state.currentRoom],
        activities: action.activities,
        isActive: false,
        buttons: [] 
      }
      newRemote.isActive = isActive(state.currentRoom, state.currentActivity, newRemote)

      const newRemotes = state.remotes.clone()
      newRemotes.set(action.remote, newRemote)

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
        action.payload
          .filter(button => (button.isActive || false))
          .map(button => [button.room || '', button.name])
      )

      if (state.currentActivity != current) {
        const activity = current.get(state.currentRoom) || ''
        return controller({ ...state, activities: action.payload, currentActivity: current },  setActivity(state.currentRoom, activity))
      } else {
        return { ...state, activities: action.payload };
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
          if (button.name == action.plug) {
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
      return { ... state, buttons: buttons }
    default:
      console.log("unmatched action " + action)
      return state;
  }
}
