import { mapToButton } from '../buttons/index';
import { RemoteData } from '../../types/index';
import { TSMap } from "typescript-map";

const baseURL = `${location.protocol}//${location.hostname}:8080`;

export function fetchRemotesAsync(): Promise<TSMap<string, RemoteData>> {
  const membersURL = `${baseURL}/config/remotes`;

  const remotesKey = 'remotes'

  const cached = mapToRemotes(JSON.parse((localStorage.getItem(remotesKey) || '[]')));

  return fetch(membersURL)
    .then((response) => response.json())
    .then((remoteJson) => mapToRemotes(remoteJson[remotesKey]))
    .then((remotes) => {
      const rs = remotes.clone()
      remotes.forEach((value, key, index) => {
        const k = key || ''
        value.isActive = (cached.get(k) || value).isActive
      })
      return rs
    })
    .then((remotes) => {
      localStorage.setItem(remotesKey, JSON.stringify(remotes.values()))
      return remotes
    });
};

function mapToRemotes(remoteData: any[]): TSMap<string, RemoteData> {
  return new TSMap<string, RemoteData> (remoteData.map(mapToRemote));
};

function mapToRemote(remote: any): [string, RemoteData] {
  return [remote.name, {
    name: remote.name,
    activities: (remote.activities || []),
    isActive: (remote.isActive || false),
    buttons: (remote.buttons.map(mapToButton) || []),
  }];
};

export const remotesAPI = {
  fetchRemotesAsync,
};
