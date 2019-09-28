import { mapToButton } from '../buttons/index';
import { RemoteData } from '../../types/index';
import { TSMap } from "typescript-map";

const baseURL = `${window.location.protocol}//${window.location.hostname}:8090`;

export async function fetchRemotesAsync(): Promise<TSMap<string, RemoteData>> {
  const membersURL = `${baseURL}/config/remotes`;

  return fetch(membersURL)
    .then((response) => response.json())
    .then((remoteJson) => mapToRemotes(remoteJson));
};

export function mapToRemotes(remoteData: any): TSMap<string, RemoteData> {
  const remotes = new TSMap<string, RemoteData> ()

  for (let key in remoteData.values) {
    let val = remoteData.values[key];
    remotes.set(key, mapToRemote(val))
  }

  return remotes;
}

function mapToRemote(remote: any): RemoteData {
  return {
    name: remote.name,
    label: remote.label,
    activities: (remote.activities || []),
    isActive: (remote.isActive || false),
    buttons: (remote.buttons.map(mapToButton) || []),
    rooms: (remote.rooms || []),
    order: remote.order,
    editable: remote.editable,
  };
};

export const remotesAPI = {
  fetchRemotesAsync,
};
