import * as React from 'react';
import * as ReactDOM from 'react-dom';
import registerServiceWorker from './registerServiceWorker';
import './index.css';
import Hello from './containers/Hello';
import MainButtons from './containers/MainButtons';
import Activities from './containers/Activities';
import ToggleShowAll from './containers/ToggleShowAll';

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
  currentActivity: '',
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
    <Hello />
  </Provider>,
  document.getElementById('remotes') as HTMLElement
);

ReactDOM.render(
  <Provider store={store}>
    <ToggleShowAll />
  </Provider>,
  document.getElementById('toggle-all') as HTMLElement
);

registerServiceWorker();
