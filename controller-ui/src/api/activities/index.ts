import { ActivityData, ContextButtons } from "../../types/index";
import { TSMap } from "typescript-map";
import { baseURL } from '../../common/Api';

export async function fetchActivitiesAsync(): Promise<
  TSMap<string, ActivityData>
> {
  const activitiesUrl = `${baseURL}/config/activities`;

  return fetch(activitiesUrl)
    .then(response => response.json())
    .then(mapToActivities);
}

export function mapToActivities(data: any): TSMap<string, ActivityData> {
  const activities = new TSMap<string, ActivityData>();

  for (let key in data.values) {
    let val = data.values[key];
    activities.set(key, mapToActivity(val));
  }

  return activities;
}

function mapToActivity(activity: any): ActivityData {
  return {
    name: activity.name,
    label: activity.label,
    room: activity.room,
    isActive: activity.isActive || false,
    order: activity.order,
    contextButtons: activity.contextButtons.map(mapToContext) || [],
    editable: activity.editable
  };
}

function mapToContext(button: any): ContextButtons {
  switch (button.type) {
    case "Remote":
      return { ...button, tag: "remote" };
    case "Macro":
      return { ...button, tag: "macro" };
    default:
      return button;
  }
}

export const activitiesAPI = {
  fetchActivitiesAsync
};
