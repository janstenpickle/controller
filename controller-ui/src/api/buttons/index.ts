import { RemoteButtons } from "../../types/index";
import { baseURL } from '../../common/Api';

export async function fetchButtonsAsync(): Promise<RemoteButtons[]> {
  const buttonsUrl = `${baseURL}/config/buttons`;

  return fetch(buttonsUrl)
    .then(response => response.json())
    .then(mapToButtons);
}

export function mapToButtons(data: any): RemoteButtons[] {
  const buttons: RemoteButtons[] = [];

  for (let key in data.values) {
    let val = data.values[key];
    buttons.push(mapToButton(val));
  }
  
  return buttons;
}

export function mapToButton(button: any): RemoteButtons {
  switch (button.type) {
    case "RemoteIcon":
      return { ...button, tag: "remote", renderTag: "icon" };
    case "RemoteLabel":
      return { ...button, tag: "remote", renderTag: "label" };
    case "SwitchIcon":
      return { ...button, tag: "switch", renderTag: "icon" };
    case "SwitchLabel":
      return { ...button, tag: "switch", renderTag: "label" };
    case "MacroIcon":
      return { ...button, tag: "macro", renderTag: "icon" };
    case "MacroLabel":
      return { ...button, tag: "macro", renderTag: "label" };
    case "ContextIcon":
      return { ...button, tag: "context", renderTag: "icon" };
    case "ContextLabel":
      return { ...button, tag: "context", renderTag: "label" };
    default:
      return button;
  }
}

export const buttonsAPI = {
  fetchButtonsAsync
};
