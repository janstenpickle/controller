import { ActivityButton } from '../../types/index';

const baseURL = `${location.protocol}//${location.hostname}:8080`;

export function fetchActivitiesAsync(): Promise<ActivityButton[]> {
  const activitiesUrl = `${baseURL}/config/activities`;

  return fetch(activitiesUrl)
    .then((response) => (response.json()))
    .then(mapToButtons);
};

function mapToButtons(data: any): ActivityButton[] {
  return data.activities.map(mapToButton);
};

function mapToButton(button: any): ActivityButton {
  return { 
    ... button,
    tag: 'activity'
   };
};

export const activitiesAPI = {
  fetchActivitiesAsync,
};
