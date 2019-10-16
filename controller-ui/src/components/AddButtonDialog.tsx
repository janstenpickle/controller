import * as React from "react";
import Select, { Option } from "@material/react-select";
import ActionSelector from "./ActionSelector";
import { buttonActionChange, editLabelOrIcon } from "../common/ButtonOps";
import { RemoteButtons } from "../types/index";
import { TSMap } from "typescript-map";
import Form from "./Form";
import Checkbox from "@material/react-checkbox";

interface Props {
  isOpen: boolean;
  buttons: string[];
  onRequestClose: () => void;
  onSuccess: (button: RemoteButtons, index: number) => void;
}

interface DialogState {
  button?: RemoteButtons;
  buttonIndex: number;
  addAfter: string;
}

export default class AddButtonDialog extends React.Component<
  Props,
  DialogState
> {
  state: DialogState = {
    buttonIndex: 0,
    addAfter: ""
  };

  public render() {
    const currentValue = () => {
      const button = this.state.button;
      if (button) {
        return button.renderTag.valueOf();
      } else {
        return "";
      }
    };

    const others = () => {
      const options = this.props.buttons.map((button, i) => (
        <Option value={i + 1} key={button}>
          {button}
        </Option>
      ));
      options.push(<Option value={0} key={""}></Option>);
      return options;
    };

    const buttonAction = (button?: RemoteButtons) => (options: string[]) => {
      if (button) {
        return this.setState({ button: buttonActionChange(options, button) });
      } else {
        return {};
      }
    };

    const checkbox = (
      checked: (button: RemoteButtons) => boolean,
      update: (button: RemoteButtons, checked: boolean) => RemoteButtons
    ) => {
      const button = this.state.button;

      if (button) {
        return (
          <Checkbox
            checked={checked(button)}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) => {
              this.setState({
                button: update(button, e.target.checked)
              });
            }}
          />
        );
      } else {
        return <Checkbox checked={false} disabled={true}></Checkbox>;
      }
    };

    const colored = () =>
      checkbox(
        button => button.colored || false,
        (button, checked) => {
          button.colored = checked;
          return button;
        }
      );

    const newRow = () =>
      checkbox(
        button => button.newRow || false,
        (button, checked) => {
          button.newRow = checked;
          return button;
        }
      );

    const cascader = (
      <ActionSelector
        placeholder=""
        onChange={buttonAction(this.state.button)}
      ></ActionSelector>
    );

    const submitButton = () => {
      const button = this.state.button;
      if (button) {
        this.props.onSuccess(button, this.state.buttonIndex);
      }
    };

    const buttonType = (
      <Select
        label="Button Type"
        outlined={true}
        value={currentValue()}
        selectClassName={"controller-width-class"}
        onChange={(e: React.ChangeEvent<HTMLSelectElement>) => {
          if (e.currentTarget.value === "icon") {
            this.setState({
              button: {
                tag: "macro",
                renderTag: "icon",
                name: "",
                icon: "add"
              }
            });
          } else if (e.currentTarget.value === "label") {
            this.setState({
              button: {
                tag: "macro",
                renderTag: "label",
                name: "",
                label: ""
              }
            });
          }
        }}
      >
        <Option disabled={true}></Option>
        <Option value={"icon"}>Icon</Option>
        <Option value={"label"}>Label</Option>
      </Select>
    );

    const labelOrIcon = () => {
      const button = this.state.button;
      if (button) {
        switch (button.renderTag) {
          case "label":
            return "Label";
          case "icon":
            return "Icon";
          default:
            return "";
        }
      } else {
        return "";
      }
    };

    const elements = () => {
      const map = new TSMap<string, JSX.Element>().set(
        "Button Type",
        buttonType
      );
      if (this.state.button) {
        map.set(
          labelOrIcon(),
          editLabelOrIcon(
            (button: RemoteButtons) => this.setState({ button }),
            this.state.button
          )
        );
      }
      map
        .set(
          "Add After",
          <Select
            label="Add after"
            outlined={true}
            value={this.state.addAfter}
            selectClassName={"controller-width-class"}
            onChange={(e: React.ChangeEvent<HTMLSelectElement>) => {
              this.setState({
                buttonIndex: this.props.buttons.indexOf(e.currentTarget.value),
                addAfter: e.currentTarget.value
              });
            }}
          >
            {others()}
          </Select>
        )
        .set("Command", cascader)
        .set("Colored", colored())
        .set("New Row", newRow());

      return map;
    };

    return (
      <Form
        name={"Add Button"}
        isOpen={this.props.isOpen}
        onCancel={this.props.onRequestClose}
        onSubmit={submitButton}
        elements={elements()}
      ></Form>
    );
  }
}
