import { mapToButtons } from '../../api/buttons';
import ReconnectingWebSocket from 'reconnecting-websocket';
import { RemoteButtons } from '../../types';
import { baseWebsocketURL } from '../../common/Api';

const socket: ReconnectingWebSocket = new ReconnectingWebSocket(`${baseWebsocketURL}/config/buttons/ws`);

export const buttonsWs = (dispatch: ((_: RemoteButtons[]) => void)) => socket.onmessage = (message: MessageEvent) => 
    dispatch(mapToButtons(JSON.parse(message.data)))