import { mapToButton } from '../buttons/index';
import { RemoteData } from '../../types/index';
import { TSMap } from "typescript-map";

const baseURL = `${location.protocol}//${location.hostname}:8090`;
const remotesKey = 'remotes'

export function fetchRemotesAsync(): Promise<TSMap<string, RemoteData>> {
  const membersURL = `${baseURL}/config/remotes`;

  return fetch(membersURL)
    .then((response) => response.json())
    .then((remoteJson) => cachedMapToRemotes(remoteJson[remotesKey]));
};

export function cachedMapToRemotes(remoteData: any[]): TSMap<string, RemoteData> {
  const cached = mapToRemotes(JSON.parse((localStorage.getItem(remotesKey) || '[]')));
  const rs =  mapToRemotes(remoteData);
  rs.forEach((value, key, index) => {
    const k = key || ''
    value.isActive = (cached.get(k) || value).isActive
  })
  localStorage.setItem(remotesKey, JSON.stringify(rs.values()))
  return rs
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
  }];
};

export const remotesAPI = {
  fetchRemotesAsync,
};
