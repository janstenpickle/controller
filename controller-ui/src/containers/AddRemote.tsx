import AddRemote from '../components/AddRemote';
import { StoreState } from '../types/index';
import { connect } from 'react-redux';
import * as actions from '../actions/';
import { Dispatch } from 'react';

export function mapStateToProps({ remotes }: StoreState) {
  return {
    remotes: remotes.values()
  };
}

export function mapDispatchToProps(dispatch: Dispatch<any>) {
  return {
    openModal: () => dispatch(actions.openDialog())
  };
}

export default connect(mapStateToProps, mapDispatchToProps)(AddRemote);
