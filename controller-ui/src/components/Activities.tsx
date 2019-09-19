import * as React from 'react';
import { ActivityButton } from '../types/index';

interface Props {
  activities: ActivityButton[];
  currentRoom: string;
  currentActivity: string;
  activate: (room:string, activity: string) => void;
  fetchActivities(): void;
}

export function renderButton(buttonData: ActivityButton, currentActivity: string, currentRoom: string, activate: (room:string, activity: string) => void) {

  const callRest = () => {
    fetch(`${location.protocol}//${location.hostname}:8090/control/activity/${currentRoom}`, { method: "POST", body: buttonData.name })
    activate(currentRoom, buttonData.name)
  }

  const baseClass = 'mdl-button mdl-js-button mdl-js-ripple-effect mdl-button--raised'

  const colored = (activityName: string) => {
    if (activityName === currentActivity)  {
      return baseClass + ' mdl-button--colored';
    } else {
      return baseClass;
    }
  }

  return(
    <div key={buttonData.name} className='button'>
      <button className={colored(buttonData.name)} onClick={callRest}>
        {(buttonData.label || buttonData.name)}
      </button>
    </div>
  );
}

export default class Activities extends React.Component<Props,{}> {
  public componentDidMount() {
    this.props.fetchActivities();
  }

  public render() {
    const renderedButtons = this.props.activities
      .filter((buttonData: ActivityButton) => (buttonData.room || '') === this.props.currentRoom )
      .map((buttonData: ActivityButton) =>  renderButton(buttonData, this.props.currentActivity, this.props.currentRoom, this.props.activate));

    return(<div className='center-align mdl-cell--12-col'>
      {renderedButtons}
    </div>);
  }
};
