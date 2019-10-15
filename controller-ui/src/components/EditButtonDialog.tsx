import * as React from "react";
import ActionSelector from "./ActionSelector";
import Form from "./Form";
import {
  buttonActionChange,
  buttonSubmit,
  editLabelOrIcon,
  updateRemote
} from "../common/ButtonOps";
import { RemoteButtons, RemoteData } from "../types/index";
import { TSMap } from "typescript-map";
import Confirmation from "./Confirmation";

interface Props {
  isOpen: boolean;
  onRequestClose: () => void;
  remote: RemoteData;
  button: RemoteButtons;
  onSuccess: (remote: RemoteData) => void;
}

interface DialogState {
  button: RemoteButtons;
  buttonIndex: number;
  confirmOpen: boolean;
}

export default class EditButtonDialog extends React.Component<
  Props,
  DialogState
> {
  state: DialogState = {
    button: this.props.button,
    buttonIndex: this.props.remote.buttons.indexOf(this.props.button),
    confirmOpen: false
  };

  public render() {
    const buttonAction = (button: RemoteButtons) => (options: string[]) => {
      return this.setState({ button: buttonActionChange(options, button) });
    };

    const cascader = (
      <ActionSelector
        placeholder=""
        onChange={buttonAction(this.state.button)}
      ></ActionSelector>
    );

    const submitButton = () => {
      buttonSubmit(
        this.props.remote,
        this.state.button,
        this.state.buttonIndex,
        this.props.onSuccess,
        true
      );
    };

    const deleteButton = () => {
      const buttons = this.props.remote.buttons.filter(
        b => b.name !== this.props.button.name
      );
      const remote: RemoteData = { ...this.props.remote, buttons: buttons };
      updateRemote(remote, this.props.onSuccess);
    };

    const labelOrIcon = () => {
      switch (this.state.button.renderTag) {
        case "label":
          return "Label";
        case "icon":
          return "Icon";
      }
    };

    const elements = new TSMap<string, JSX.Element>()
      .set(
        labelOrIcon(),
        editLabelOrIcon(
          (button: RemoteButtons) => this.setState({ button }),
          this.state.button
        )
      )
      .set("Command", cascader);

    return (
      <React.Fragment>
        <Confirmation
          isOpen={this.state.confirmOpen}
          message="Are you sure you want to delete this button?"
          onOk={deleteButton}
          onCancel={() => this.setState({ confirmOpen: false })}
        ></Confirmation>
        <Form
          name={"Edit Button"}
          isOpen={this.props.isOpen}
          onCancel={this.props.onRequestClose}
          onSubmit={submitButton}
          onDelete={() => this.setState({ confirmOpen: true })}
          elements={elements}
        ></Form>
      </React.Fragment>
    );
  }
}
