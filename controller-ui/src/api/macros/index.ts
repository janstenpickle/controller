
import { baseURL } from '../../common/Api';

export async function fetchMacrosAsync(): Promise<string[]> {
  const macrosUrl = `${baseURL}/control/macro`;

  return fetch(macrosUrl)
    .then((response) => (response.json()))
    .then(mapToMacros);
};

export function mapToMacros(data: any): string[] {
    return data.map((macro: any) => macro as string);
};

export const macrosAPI = {
  fetchMacrosAsync
};
