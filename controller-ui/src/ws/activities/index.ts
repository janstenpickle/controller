import { mapToActivities } from '../../api/activities';
import ReconnectingWebSocket from 'reconnecting-websocket';
import { ActivityData } from '../../types';
import { TSMap } from 'typescript-map';

const baseURL = `ws://${window.location.hostname}:8090`;
const socket: ReconnectingWebSocket = new ReconnectingWebSocket(`${baseURL}/config/activities/ws`);

export const activitiesWs = (dispatch: ((_: TSMap<string, ActivityData>) => void)) => socket.onmessage = (message: MessageEvent) =>
    dispatch(mapToActivities(JSON.parse(message.data)))