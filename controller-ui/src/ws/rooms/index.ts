import { mapToRooms } from '../../api/rooms';
import ReconnectingWebSocket from 'reconnecting-websocket';
import { baseWebsocketURL } from '../../common/Api';

const socket: ReconnectingWebSocket = new ReconnectingWebSocket(`${baseWebsocketURL}/config/rooms/ws`);

export const roomsWs = (dispatch: ((_: string[]) => void)) => socket.onmessage = (message: MessageEvent) =>
    dispatch(mapToRooms(JSON.parse(message.data)))