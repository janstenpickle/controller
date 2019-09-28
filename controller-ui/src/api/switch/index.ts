import { Switch } from "../../types";

const baseURL = `${window.location.protocol}//${window.location.hostname}:8090`;

export async function fetchSwitchesAsync(): Promise<Switch[]> {
  const remoteControlUrl = `${baseURL}/control/switch`;

  return fetch(remoteControlUrl)
    .then((response) => (response.json()))
    .then(mapToSwitches);
};

function mapToSwitches(data: any): Switch[] {
    return data.map((d: any) => ({ ...d }));
};

export const switchesApi = {
  fetchSwitchesAsync
};
