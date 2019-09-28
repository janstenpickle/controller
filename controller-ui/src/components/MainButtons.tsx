import * as React from "react";
import { RemoteButtons } from "../types/index";
import { StyleSheet, css } from "aphrodite";

interface Props {
  buttons: RemoteButtons[];
  currentRoom: string;
  fetchButtons(): void;
  plugState(state: boolean, name?: string): void;
}

const styles = StyleSheet.create({
  red: {
    backgroundColor: "red"
  },

  blue: {
    backgroundColor: "blue"
  },

  green: {
    backgroundColor: "green"
  },

  white: {
    backgroundColor: "white"
  },

  yellow: {
    backgroundColor: "yellow"
  }
});

export function renderButton(
  buttonData: RemoteButtons,
  currentRoom: string,
  plugState: (state: boolean, name?: string) => void,
  editMode: boolean,
  edit: (buttonData: RemoteButtons) => void
) {
  const baseClass = "mdc-ripple-upgraded";

  const buttonType = () => {
    switch (buttonData.renderTag) {
      case "icon":
        return " mdc-fab mdc-fab--mini mdc-fab--color-override";
      case "label":
        return " mdc-button mdc-button--dense mdc-button--outlined mdc-elevation--z2";
    }
  };

  const colorStyle = () => {
    switch (buttonData.color) {
      case "red":
        return css(styles.red);
      case "blue":
        return css(styles.blue);
      case "green":
        return css(styles.green);
      case "white":
        return css(styles.white);
      case "yellow":
        return css(styles.yellow);
      default:
        return "mdc-button--raised";
    }
  };

  const colored = () => {
    const baseColored = baseClass + buttonType();

    switch (buttonData.tag) {
      case "switch":
        if (buttonData.isOn) {
          return baseColored + " " + colorStyle();
        } else {
          return baseColored;
        }
      case "macro":
        if (buttonData.isOn) {
          return baseColored + " " + colorStyle();
        } else {
          return baseColored;
        }
      default:
        if (buttonData.colored) {
          return baseColored + " " + colorStyle();
        } else {
          return baseColored;
        }
    }
  };

  const buttonAction = () => {
    if (editMode) {
      edit(buttonData);
    } else {
      callRest();
    }
  };

  const callRest = () => {
    switch (buttonData.tag) {
      case "remote":
        return fetch(
          `${window.location.protocol}//${window.location.hostname}:8090/control/remote/send/${buttonData.remote}/${buttonData.device}/${buttonData.name}`,
          { method: "POST" }
        );
      case "macro":
        if (buttonData.isOn !== undefined) {
          plugState(!buttonData.isOn, buttonData.name);
        }
        return fetch(
          `${window.location.protocol}//${window.location.hostname}:8090/control/macro/send/${buttonData.name}`,
          { method: "POST" }
        );
      case "switch":
        plugState(!buttonData.isOn, buttonData.name);
        return fetch(
          `${window.location.protocol}//${window.location.hostname}:8090/control/switch/toggle/${buttonData.device}/${buttonData.name}`,
          { method: "POST" }
        );
      case "context":
        return fetch(
          `${window.location.protocol}//${window.location.hostname}:8090/control/context/${currentRoom}/${buttonData.name}`,
          { method: "POST" }
        );
    }
  };

  const content = () => {
    switch (buttonData.renderTag) {
      case "icon":
        return (
          <span className="mdc-fab__icon material-icons">
            {buttonData.icon}
          </span>
        );
      case "label":
        return buttonData.label;
    }
  };

  const name = () => {
    switch (buttonData.tag) {
      case "remote":
        return (
          buttonData.remote + "-" + buttonData.device + "-" + buttonData.name
        );
      case "switch":
        return buttonData.device + "-" + buttonData.name;
      case "macro":
        return buttonData.name;
      case "context":
        return buttonData.name;
    }
  };

  const className = () => {
    if (buttonData.newRow) {
      return "newrow-button";
    } else {
      return "button";
    }
  };

  return (
    <div key={name()} className={className()}>
      <button className={colored()} onClick={buttonAction}>
        {content()}
      </button>
    </div>
  );
}

export default class Remotes extends React.Component<Props, {}> {
  public componentDidMount() {
    this.props.fetchButtons();
  }

  public render() {
    const renderedButtons = this.props.buttons
      .filter(
        (buttonData: RemoteButtons) =>
          (buttonData.room || "") === this.props.currentRoom
      )
      .map((buttonData: RemoteButtons) =>
        renderButton(
          buttonData,
          this.props.currentRoom,
          this.props.plugState,
          false,
          _ => {}
        )
      );

    return <div>{renderedButtons}</div>;
  }
}
