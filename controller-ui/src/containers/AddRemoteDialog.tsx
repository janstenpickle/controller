import { StoreState, RemoteData, ActivityData } from '../types/index';
import { connect } from 'react-redux';
import { Dispatch } from 'react';
import * as actions from '../actions/';
import AddRemoteDialog from '../components/AddRemoteDialog';

export function mapStateToProps({ activities, currentRoom, editMode, remotes }: StoreState) {
  return {
    activities: activities.clone().values().filter((activity: ActivityData) => activity.room === currentRoom).map((activity: ActivityData) => activity.name),
    currentRoom: currentRoom,
    editMode: editMode,
    remotes: remotes.clone().filter((remote: RemoteData) => remote.rooms.includes(currentRoom) || remote.rooms.length === 0)
  };
}

export function mapDispatchToProps(dispatch: Dispatch<any>) {
  return {
    addRemote: (remote: RemoteData) => dispatch(actions.addRemote(remote)),
    fetchActivities: () => dispatch(actions.loadActivitiesAction()),
  };
}

export default connect(mapStateToProps, mapDispatchToProps)(AddRemoteDialog);
