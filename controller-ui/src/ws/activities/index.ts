import { mapToButtons } from '../../api/activities';
import ReconnectingWebSocket from 'reconnecting-websocket';
import { ActivityButton } from '../../types';

const baseURL = `ws://${location.hostname}:8090`;
const socket: ReconnectingWebSocket = new ReconnectingWebSocket(`${baseURL}/config/activities/ws`);

export const activitiesWs = (dispatch: ((_: ActivityButton[]) => void)) => socket.onmessage = (message: MessageEvent) =>
    dispatch(mapToButtons(JSON.parse(message.data)))