import * as React from "react";
import ActionSelector from "./ActionSelector";
import Form from "./Form";
import { buttonActionChange, editLabelOrIcon } from "../common/ButtonOps";
import { RemoteButtons } from "../types/index";
import { TSMap } from "typescript-map";
import Confirmation from "./Confirmation";

interface Props {
  isOpen: boolean;
  onRequestClose: () => void;
  button: RemoteButtons;
  onSuccess: (button: RemoteButtons) => void;
  onDelete: () => void;
}

interface DialogState {
  button?: RemoteButtons;
  confirmOpen: boolean;
}

export default class EditButtonDialog extends React.Component<
  Props,
  DialogState
> {
  state: DialogState = {
    confirmOpen: false
  };

  public render() {
    const buttonAction = (button: RemoteButtons) => (options: string[]) => {
      return this.setState({ button: buttonActionChange(options, button) });
    };

    const cascader = (
      <ActionSelector
        placeholder=""
        onChange={buttonAction(this.props.button)}
      ></ActionSelector>
    );

    const submitButton = () => {
      if (this.state.button) {
        this.props.onSuccess(this.state.button);
      }
    };

    const labelOrIcon = () => {
      switch (this.props.button.renderTag) {
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
          this.props.button
        )
      )
      .set("Command", cascader);

    return (
      <React.Fragment>
        <Confirmation
          isOpen={this.state.confirmOpen}
          message="Are you sure you want to delete this button?"
          onOk={this.props.onDelete}
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
