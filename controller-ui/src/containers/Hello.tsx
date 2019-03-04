import Hello from '../components/Hello';
import * as actions from '../actions/';
import { StoreState } from '../types/index';
import { connect } from 'react-redux';

export function mapStateToProps({ remotes, focusedRemote, showAll }: StoreState) {
  return {
    remotes: remotes.values(),
    focusedRemote: focusedRemote,
    showAll: showAll,
  };
}

// export function mapDispatchToProps(dispatch: Dispatch<actions.EnthusiasmAction>) {
//   return {
//     focus: (remote: string) => dispatch(actions.focusRemote(remote)),
//     fetchRemotes: () => dispatch(actions.loadRemotesAction()),
//   };
// }

const mapDispatchToProps = (dispatch: any) => ({
  focus: (remote: string) => dispatch(actions.focusRemote(remote)),
  fetchRemotes: () => dispatch(actions.loadRemotesAction()),
  plugState: (state: boolean, name?: string) => dispatch(actions.updatePlugState(state, name)),
});

export default connect(mapStateToProps, mapDispatchToProps)(Hello);
