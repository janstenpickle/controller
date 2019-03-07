import { mapToRooms } from '../../api/rooms';
import ReconnectingWebSocket from 'reconnecting-websocket';

const baseURL = `ws://${location.hostname}:8090`;
const socket: ReconnectingWebSocket = new ReconnectingWebSocket(`${baseURL}/config/rooms/ws`);

export const roomsWs = (dispatch: ((_: string[]) => void)) => socket.onmessage = (message: MessageEvent) =>
    dispatch(mapToRooms(JSON.parse(message.data)))