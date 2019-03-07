import * as React from 'react';
import { RemoteButtons } from '../types/index';

interface Props {
  buttons: RemoteButtons[];
  currentRoom: string;
  fetchButtons(): void;
  plugState(state: boolean, name?: string): void;
}

export function renderButton(buttonData: RemoteButtons, currentRoom: string, plugState: (state: boolean, name?: string) => void) {
  const baseClass = 'mdl-button mdl-js-button mdl-js-ripple-effect'

  const buttonType = () => {
    switch(buttonData.renderTag) {
      case 'icon': return ' mdl-button--fab mdl-button--mini-fab mdl-button-size-override'
      case 'label': return ' mdl-button--raised'
    }
  }

  const colored = () => {
    const baseColored = baseClass + buttonType();

    switch(buttonData.tag) {
      case 'switch': if (buttonData.isOn) {
        return baseColored + ' mdl-button--colored';
      } else {
        return baseColored;
      }
      case 'macro': if (buttonData.isOn) {
        return baseColored + ' mdl-button--colored';
      } else {
        return baseColored;
      }
      default: if (buttonData.colored) {
        return baseColored + ' mdl-button--colored';
      } else {
        return baseColored;
      };
    }
  }

  const callRest = () => {
    switch(buttonData.tag) {
      case 'remote': 
        return fetch(`${location.protocol}//${location.hostname}:8090/control/remote/send/${buttonData.remote}/${buttonData.device}/${buttonData.name}`, { method: 'POST' })
      case 'macro':
        if (buttonData.isOn !== undefined) {
          plugState(!buttonData.isOn, buttonData.name)
        }
        return fetch(`${location.protocol}//${location.hostname}:8090/control/macro/send/${buttonData.name}`, { method: 'POST' })
      case 'switch':
        plugState(!buttonData.isOn, buttonData.name)
        return fetch(`${location.protocol}//${location.hostname}:8090/control/switch/toggle/${buttonData.device}/${buttonData.name}`, { method: 'POST' })
      case 'context': 
        return fetch(`${location.protocol}//${location.hostname}:8090/control/context/${currentRoom}/${buttonData.name}`, { method: 'POST' })
    }
  }

  const content = () => {
    switch(buttonData.renderTag) {
      case 'icon': return <i className='material-icons'>{buttonData.icon}</i>
      case 'label': return buttonData.label
    }
  }


  const name = () => {
    switch(buttonData.tag) {
      case 'remote': return buttonData.remote + '-' + buttonData.device + '-' + buttonData.name
      case 'switch': return buttonData.device + '-' + buttonData.name
      case 'macro': return buttonData.name
      case 'context': return buttonData.name
    }
  }

  const className = () => {
    if (buttonData.newRow) {
      return 'newrow-button'
    } else {
      return 'button'
    }
  }

  return(
    <div key={name()} className={className()}>
      <button className={colored()} onClick={callRest}>
        {content()}
      </button>
    </div>
  );
}

export default class Remotes extends React.Component<Props,{}> {
  public componentDidMount() {
    this.props.fetchButtons();
  }

  public render() {
    const renderedButtons = this.props.buttons
      .filter((buttonData: RemoteButtons) => (buttonData.room || '') === this.props.currentRoom )
      .map((buttonData: RemoteButtons) =>  renderButton(buttonData, this.props.currentRoom, this.props.plugState));

    return(<div>
      {renderedButtons}
    </div>);
  }
};
