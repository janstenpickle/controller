import { mapToActivities } from '../../api/activities';
import ReconnectingWebSocket from 'reconnecting-websocket';
import { ActivityData } from '../../types';
import { TSMap } from 'typescript-map';
import { baseWebsocketURL } from '../../common/Api';

const socket: ReconnectingWebSocket = new ReconnectingWebSocket(`${baseWebsocketURL}/config/activities/ws`);

export const activitiesWs = (dispatch: ((_: TSMap<string, ActivityData>) => void)) => socket.onmessage = (message: MessageEvent) =>
    dispatch(mapToActivities(JSON.parse(message.data)))