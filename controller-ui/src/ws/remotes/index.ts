import { mapToRemotes } from '../../api/remotes';
import ReconnectingWebSocket from 'reconnecting-websocket';
import { RemoteData } from '../../types';
import { TSMap } from "typescript-map";
import { baseWebsocketURL } from '../../common/Api';

const socket: ReconnectingWebSocket = new ReconnectingWebSocket(`${baseWebsocketURL}/config/remotes/ws`);

export const remotesWs = (dispatch: ((_: TSMap<string, RemoteData>) => void)) => socket.onmessage = (message: MessageEvent) =>
    dispatch(mapToRemotes(JSON.parse(message.data)))
