import { mapToButton } from '../buttons/index';
import { RemoteData } from '../../types/index';
import { TSMap } from "typescript-map";

const baseURL = `${location.protocol}//${location.hostname}:8090`;
const remotesKey = 'remotes'

export function fetchRemotesAsync(): Promise<TSMap<string, RemoteData>> {
  const membersURL = `${baseURL}/config/remotes`;

  return fetch(membersURL)
    .then((response) => response.json())
    .then((remoteJson) => mapToRemotes(remoteJson[remotesKey]));
};

export function mapToRemotes(remoteData: any[]): TSMap<string, RemoteData> {
  return new TSMap<string, RemoteData> (remoteData.map(mapToRemote));
}

function mapToRemote(remote: any): [string, RemoteData] {
  return [remote.name, {
    name: remote.name,
    activities: (remote.activities || []),
    isActive: (remote.isActive || false),
    buttons: (remote.buttons.map(mapToButton) || []),
    rooms: (remote.rooms || [])
  }];
};

export const remotesAPI = {
  fetchRemotesAsync,
};
