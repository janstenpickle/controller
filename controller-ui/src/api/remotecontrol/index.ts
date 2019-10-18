import { RemoteCommand } from "../../types";
import { baseURL } from '../../common/Api';

export async function fetchRemoteCommandsAsync(): Promise<RemoteCommand[]> {
  const remoteControlUrl = `${baseURL}/control/remote`;

  return fetch(remoteControlUrl)
    .then((response) => (response.json()))
    .then(mapToRemoteCommands);
};

function mapToRemoteCommands(data: any): RemoteCommand[] {
    return data.map(mapToRemoteCommand);
};

function mapToRemoteCommand(data: any): RemoteCommand {
  return { ...data };
};

export const remoteCommandsAPI = {
  fetchRemoteCommandsAsync
};
