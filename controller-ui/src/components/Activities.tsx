import * as React from 'react';
import { ActivityButton } from '../types/index';

interface Props {
  activities: ActivityButton[];
  currentActivity: string;
  activate: (activity: string) => void;
  fetchActivities(): void;
}

export function renderButton(buttonData: ActivityButton, currentActivity: String, activate: (activity: string) => void) {

  const callRest = () => {
    fetch(`${location.protocol}//${location.hostname}:8090/control/activity`, { method: "POST", body: buttonData.name })
    activate(buttonData.name)
  }

  const baseClass = 'mdl-button mdl-js-button mdl-js-ripple-effect mdl-button--raised'

  const colored = (activityName: String) => {
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
    const renderedButtons = this.props.activities.map((buttonData: ActivityButton) =>  renderButton(buttonData, this.props.currentActivity, this.props.activate));

    return(<div className='center-align mdl-color--grey-100 mdl-cell--12-col'>
      {renderedButtons}
    </div>);
  }
};
