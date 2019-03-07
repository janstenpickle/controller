import * as React from 'react';
import * as ReactDOM from 'react-dom';
import registerServiceWorker from './registerServiceWorker';
import './index.css';
import Remotes from './containers/Remotes';
import MainButtons from './containers/MainButtons';
import Activities from './containers/Activities';
import ToggleShowAll from './containers/ToggleShowAll';
import Rooms from './containers/Rooms';

import { TSMap } from 'typescript-map';
import { Provider } from 'react-redux';
import { createStore, applyMiddleware } from 'redux';
import { controller } from './reducers/index';
import { StoreState, RemoteData } from './types/index';
import thunk from 'redux-thunk';
import { ControllerAction } from './actions';

const store = createStore<StoreState, ControllerAction, any, any>(controller, {
  buttons: [],
  activities: [],
  remotes: new TSMap<string, RemoteData>(),
  focusedRemote: 'TV',
  currentActivity: new TSMap<string, string>(),
  currentRoom: localStorage.getItem('room') || '',
  showAll: !(document.documentElement.clientWidth < 900)
}, applyMiddleware(thunk));

ReactDOM.render(
  <Provider store={store}>
    <Activities />
  </Provider>,
  document.getElementById('activities') as HTMLElement
);

ReactDOM.render(
  <Provider store={store}>
    <MainButtons />
  </Provider>,
  document.getElementById('main-buttons') as HTMLElement
);

ReactDOM.render(
  <Provider store={store}>
    <Remotes />
  </Provider>,
  document.getElementById('remotes') as HTMLElement
);

ReactDOM.render(
  <Provider store={store}>
    <ToggleShowAll />
  </Provider>,
  document.getElementById('toggle-all') as HTMLElement
);

ReactDOM.render(
  <Provider store={store}>
    <Rooms />
  </Provider>,
  document.getElementById('rooms-menu') as HTMLElement
);

registerServiceWorker();
