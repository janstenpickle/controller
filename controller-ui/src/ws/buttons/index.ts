import { mapToButtons } from '../../api/buttons';
import ReconnectingWebSocket from 'reconnecting-websocket';
import { RemoteButtons } from '../../types';

const baseURL = `ws://${location.hostname}:8090`;
const socket: ReconnectingWebSocket = new ReconnectingWebSocket(`${baseURL}/config/buttons/ws`);

export const buttonsWs = (dispatch: ((_: RemoteButtons[]) => void)) => socket.onmessage = (message: MessageEvent) => 
    dispatch(mapToButtons(JSON.parse(message.data)))