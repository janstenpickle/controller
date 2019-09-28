import * as React from 'react';

interface Props {
  showAll: boolean,
  toggle: () => void,
}

export default class ToggleShowAll extends React.Component<Props,{}> {
  public render() {
    const icon = () => {
      if (this.props.showAll) {
        return "stop";
      } else {
        return "widgets";
      }
    }

    const button = () => {
      if (document.documentElement.clientWidth > 900) {
        return(
          <button className="mdc-ripple-upgraded mdc-fab mdc-button--raised mdc-fab--mini" onClick={this.props.toggle}>
            <span className="mdc-fab__icon material-icons">{icon()}</span>
          </button>);
      } else {
        return '';
      }
    }

    return(button());
  }
};
