import { mapToRemotes } from '../../api/remotes';
import ReconnectingWebSocket from 'reconnecting-websocket';
import { RemoteData } from '../../types';
import { TSMap } from "typescript-map";

const baseURL = `ws://${window.location.hostname}:8090`;
const socket: ReconnectingWebSocket = new ReconnectingWebSocket(`${baseURL}/config/remotes/ws`);

export const remotesWs = (dispatch: ((_: TSMap<string, RemoteData>) => void)) => socket.onmessage = (message: MessageEvent) =>
    dispatch(mapToRemotes(JSON.parse(message.data)))
