import * as React from "react";
import { RemoteButtons } from "../types/index";
import { StyleSheet, css } from "aphrodite";
import EditButtonDialog from "./EditButtonDialog";
import AddButtonDialog from "./AddButtonDialog";
import Alert from "./Alert";
import { baseURL } from '../common/Api';

interface Props {
  buttons: RemoteButtons[];
  currentRoom: string;
  editMode: boolean;
  fetchButtons(): void;
  plugState(state: boolean, name?: string): void;
}

interface State {
  editButton: boolean;
  addButton: boolean;
  alertOpen: boolean;
  alertMessage?: string;
  button?: RemoteButtons;
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
          `${baseURL}/control/remote/send/${buttonData.remote}/${buttonData.device}/${buttonData.name}`,
          { method: "POST" }
        );
      case "macro":
        if (buttonData.isOn !== undefined) {
          plugState(!buttonData.isOn, buttonData.name);
        }
        return fetch(
          `${baseURL}/control/macro/send/${buttonData.name}`,
          { method: "POST" }
        );
      case "switch":
        plugState(!buttonData.isOn, buttonData.name);
        return fetch(
          `${baseURL}/control/switch/toggle/${buttonData.device}/${buttonData.name}`,
          { method: "POST" }
        );
      case "context":
        return fetch(
          `${baseURL}/control/context/${currentRoom}/${buttonData.name}`,
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

export default class MainButtons extends React.Component<Props, State> {
  private defaultState: State = {
    editButton: false,
    addButton: false,
    alertOpen: false
  };

  state: State = this.defaultState;

  public componentDidMount() {
    this.props.fetchButtons();
  }

  public render() {
    const alert = (message: string) =>
      this.setState({ alertMessage: message, alertOpen: true });

    const deleteButton = () => {
      const button = this.state.button
      this.setState(this.defaultState);
      if (button) {
        fetch(
          `${baseURL}/config/button/${button.name}-${this.props.currentRoom}`,
          { method: "DELETE" }
        ).then(res => {
          if (!res.ok) {
            res.text().then(text => alert(`Failed to delete button: ${text}`));
          }
        });
      }
    };

    const updateButton = (button: RemoteButtons) => {
      this.setState(this.defaultState);

      fetch(
        `${baseURL}/config/button/${button.name}-${this.props.currentRoom}`,
        { method: "PUT", body: JSON.stringify(button) }
      ).then(res => {
        if (!res.ok) {
          res.text().then(text => alert(`Failed to update button: ${text}`));
        }
      });
    };

    const createButton = (button: RemoteButtons, index: number) => {
      this.setState(this.defaultState);

      const orderedButton = {
        ...button,
        order: index,
        room: this.props.currentRoom
      };

      fetch(
        `${baseURL}/config/button`,
        { method: "POST", body: JSON.stringify(orderedButton) }
      ).then(res => {
        if (!res.ok) {
          res.text().then(text => alert(`Failed to create button: ${text}`));
        }
      });
    };

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
          this.props.editMode,
          button => this.setState({ editButton: true, button: button })
        )
      );

    const edit = () => {
      if (this.state.button) {
        return (
          <EditButtonDialog
            isOpen={this.state.editButton}
            onRequestClose={() => this.setState(this.defaultState)}
            button={this.state.button}
            onSuccess={updateButton}
            onDelete={deleteButton}
          ></EditButtonDialog>
        );
      } else {
        return <React.Fragment></React.Fragment>;
      }
    };

    const add = (
      <AddButtonDialog
        isOpen={this.state.addButton}
        onRequestClose={() => this.setState(this.defaultState)}
        buttons={this.props.buttons
          .filter(button => button.room === this.props.currentRoom)
          .map(button => button.name)}
        onSuccess={createButton}
      ></AddButtonDialog>
    );

    const addButton = () => {
      if (this.props.editMode) {
        return (
          <button
            className="'mdc-ripple-upgraded mdc-fab mdc-fab--small mdc-fab--color-override"
            onClick={() => this.setState({ addButton: true })}
          >
            <span className="mdc-fab__icon material-icons">add</span>
          </button>
        );
      } else {
        return <React.Fragment></React.Fragment>;
      }
    };

    return (
      <React.Fragment>
        <Alert
          isOpen={this.state.alertOpen}
          message={this.state.alertMessage}
          onClose={() => this.setState({ alertOpen: false })}
        ></Alert>
        {add}
        {edit()}
        {addButton()}
        {renderedButtons}
      </React.Fragment>
    );
  }
}
