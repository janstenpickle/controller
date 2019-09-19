import { StoreState } from '../types/index';
import { connect } from 'react-redux';
import { Dispatch } from 'react';
import * as actions from '../actions/';
import AddRemoteDialog from '../components/AddRemoteDialog';
import { ActivityButton } from '../types/index';

export function mapStateToProps({ activities, currentRoom, newRemoteDialogOpen }: StoreState) {
  return {
    activities: activities.filter((buttonData: ActivityButton) => buttonData.room === currentRoom).map((buttonData: ActivityButton) => buttonData.name),
    isOpen: newRemoteDialogOpen
  };
}

export function mapDispatchToProps(dispatch: Dispatch<any>) {
  return {
    openModal: () => dispatch(actions.openDialog()),
    closeModal: () => dispatch(actions.closeDialog()),
    addRemote: (remote: string, activities: string[]) => dispatch(actions.addRemote(remote, activities))
  };
}

export default connect(mapStateToProps, mapDispatchToProps)(AddRemoteDialog);
