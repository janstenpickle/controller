
import { baseURL } from '../../common/Api';

export async function fetchRoomsAsync(): Promise<string[]> {
  const roomsUrl = `${baseURL}/config/rooms`;

  return fetch(roomsUrl)
    .then((response) => (response.json()))
    .then(mapToRooms);
};

export function mapToRooms(data: any): string[] {
    return data.rooms.map((room: any) => room as string);
};

export const roomsAPI = {
  fetchRoomsAsync
};
