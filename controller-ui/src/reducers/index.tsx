import { ControllerAction, setActivity } from '../actions';
import { StoreState, RemoteButtons } from '../types/index';
import { FOCUS_REMOTE, TOGGLE_REMOTE, LOADED_BUTTONS, LOADED_REMOTES, LOADED_ACTIVITIES, SET_ACTIVITY, TOGGLE_SHOW_ALL, UPDATE_PLUG_STATE } from '../constants/index';

export function controller(state: StoreState, action: ControllerAction): StoreState {
  switch (action.type) {
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
          value.isActive = (value.activities.indexOf(state.currentActivity) > -1)
      ) 
      localStorage.setItem('remotes', JSON.stringify(rs.values()))
      return { ...state, remotes: rs }
    }
    case LOADED_ACTIVITIES:
      if (state.currentActivity != action.current) {
        return controller({ ...state, activities: action.payload },  setActivity(action.current))
      } else {
        return { ...state, activities: action.payload, currentActivity: action.current };
      }
    case TOGGLE_SHOW_ALL:
      return { ...state, showAll: !state.showAll };
    case SET_ACTIVITY: {
      const rs = state.remotes.clone()
      rs.forEach((value, key, index) =>
          value.isActive = (value.activities.indexOf(action.name) > -1)
      ) 
      localStorage.setItem('remotes', JSON.stringify(rs.values()))
      localStorage.setItem('activity', action.name)
      return { ...state, remotes: rs, currentActivity: action.name }
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
      return state;
  }
}
