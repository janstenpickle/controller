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
          <button className="mdl-button mdl-js-button mdl-button--fab mdl-button--mini-fab mdl-button--colored mdl-button-size-override" onClick={this.props.toggle}>
            <i className="material-icons">{icon()}</i>
          </button>);
      } else {
        return '';
      }
    }

    return(button());
  }
};
