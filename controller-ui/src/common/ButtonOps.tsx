import { RemoteData, RemoteButtons } from "../types/index";
import * as React from "react";
import TextField, { Input } from "@material/react-text-field";
import IconPicker from "../components/IconPicker";
import { baseURL } from "./Api";

export const buttonSubmit = (
  remote: RemoteData,
  button: RemoteButtons,
  buttonIndex: number,
  onSuccess: (remote: RemoteData) => void,
  replace: boolean = false
) => {
  const reducer: (
    acc: RemoteButtons[],
    v: RemoteButtons,
    index: number,
    arr: RemoteButtons[]
  ) => RemoteButtons[] = (
    acc: RemoteButtons[],
    v: RemoteButtons,
    index: number,
    arr: RemoteButtons[]
  ) => {
    if (index === buttonIndex && replace) {
      acc.push(button);
    } else if (index === buttonIndex && !replace) {
      acc.push(v);
      acc.push(button);
    } else {
      acc.push(v);
    }
    return acc;
  };

  const buttons = remote.buttons.reduce(reducer, []);

  remote.buttons = buttons;

  updateRemote(remote, onSuccess);
};

export const updateRemote = (
  remote: RemoteData,
  onSuccess: (remote: RemoteData) => void
) => {
  fetch(`${baseURL}/config/remote/${remote.name}`, {
    method: "PUT",
    body: JSON.stringify(remote)
  }).then(res => {
    if (res.ok) {
      onSuccess(remote);
    } else {
      alert("Failed to update remote");
    }
  });
};

export const buttonActionChange: (
  data: string[],
  button: RemoteButtons
) => RemoteButtons = (data: string[], button: RemoteButtons) => {
  if (data[0] === "remote") {
    switch (button.renderTag) {
      case "icon": {
        const source = data[2].split("|");

        return {
          ...button,
          tag: "remote",
          renderTag: "icon",
          remote: data[1],
          commandSource: {
            name: source[0],
            type: source[1]
          },
          device: data[3],
          name: data[4],
          type: "RemoteIcon"
        };
      }
      case "label": {
        const source = data[2].split("|");

        return {
          ...button,
          tag: "remote",
          renderTag: "label",
          remote: data[1],
          commandSource: {
            name: source[0],
            type: source[1]
          },
          device: data[3],
          name: data[4],
          type: "RemoteLabel"
        };
      }
      default:
        return button;
    }
  } else if (data[0] === "switch" && button) {
    switch (button.renderTag) {
      case "icon":
        return {
          ...button,
          tag: "switch",
          renderTag: "icon",
          device: data[1],
          name: data[2],
          isOn: false,
          type: "SwitchIcon"
        };
      case "label":
        return {
          ...button,
          tag: "switch",
          renderTag: "label",
          device: data[1],
          name: data[2],
          isOn: false,
          type: "SwitchLabel"
        };
      default:
        return button;
    }
  } else if (data[0] === "macro" && button) {
    switch (button.renderTag) {
      case "icon":
        return {
          ...button,
          tag: "macro",
          renderTag: "icon",
          name: data[1],
          isOn: false,
          type: "MacroIcon"
        };
      case "label":
        return {
          ...button,
          tag: "macro",
          renderTag: "label",
          name: data[1],
          isOn: false,
          type: "MacroLabel"
        };
      default:
        return button;
    }
  } else {
    return button;
  }
};

export const editLabelOrIcon = (
  onChange: (button: RemoteButtons) => void,
  button?: RemoteButtons
) => {
  if (button) {
    switch (button.renderTag) {
      case "label":
        return (
          <TextField
            outlined={true}
            dense={true}
            label="Button Label"
            className={"controller-width-class"}
          >
            <Input
              value={button.label}
              onChange={(e: React.FormEvent<HTMLInputElement>) => {
                button.label = e.currentTarget.value;
                onChange(button);
              }}
            />
          </TextField>
        );
      case "icon":
        return (
          <IconPicker
            currentValue={button.icon}
            onSubmit={icon => {
              button.icon = icon;
              onChange(button);
            }}
          ></IconPicker>
        );
      default:
        return <React.Fragment></React.Fragment>;
    }
  } else {
    return <React.Fragment></React.Fragment>;
  }
};
