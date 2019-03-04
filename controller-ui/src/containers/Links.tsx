import Links from '../components/Links';
import * as actions from '../actions/';
import { StoreState } from '../types/index';
import { connect } from 'react-redux';
import { Dispatch } from 'react';

export function mapStateToProps({ remotes }: StoreState) {
  return {
    remotes: remotes.values()
  };
}

export function mapDispatchToProps(dispatch: Dispatch<actions.ControllerAction>) {
  return {
    focus: (remote: string) => dispatch(actions.focusRemote(remote)),
    toggle: (remote: string, value: boolean) => dispatch(actions.toggleRemote(remote, value))
  };
}

export default connect(mapStateToProps, mapDispatchToProps)(Links);
