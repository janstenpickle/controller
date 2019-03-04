import { RemoteButtons } from '../../types/index';

const baseURL = `${location.protocol}//${location.hostname}:8080`;

export function fetchButtonsAsync(): Promise<RemoteButtons[]> {
  const buttonsUrl = `${baseURL}/config/buttons`;

  return fetch(buttonsUrl)
    .then((response) => response.json())
    .then(mapToButtons);
};

function mapToButtons(data: any): RemoteButtons[] {
  return data.buttons.map(mapToButton);
};

export function mapToButton(button: any): RemoteButtons {
  switch(button.type) {
    case 'RemoteIcon': return  { ... button, tag: 'remote', renderTag: 'icon' }
    case 'RemoteLabel': return  { ... button, tag: 'remote', renderTag: 'label' }
    case 'SwitchIcon': return  { ... button, tag: 'switch', renderTag: 'icon' }
    case 'SwitchLabel': return  { ... button, tag: 'switch', renderTag: 'label' }
    case 'MacroIcon': return  { ... button, tag: 'macro', renderTag: 'icon' }
    case 'MacroLabel': return  { ... button, tag: 'macro', renderTag: 'label' }
    case 'ContextIcon': return  { ... button, tag: 'context', renderTag: 'icon' }
    case 'ContextLabel': return  { ... button, tag: 'context', renderTag: 'label' }
    default: return button
  }
};

export const buttonsAPI = {
  fetchButtonsAsync,
};
