import Remotes from '../components/Remotes';
import * as actions from '../actions';
import { StoreState } from '../types/index';
import { connect } from 'react-redux';

export function mapStateToProps(state: StoreState) {
  return {
    remotes: state.remotes.values(),
    focusedRemote: state.focusedRemote,
    showAll: state.showAll,
    currentRoom: state.currentRoom
  };
}

const mapDispatchToProps = (dispatch: any) => ({
  focus: (remote: string) => dispatch(actions.focusRemote(remote)),
  fetchRemotes: () => dispatch(actions.loadRemotesAction()),
  plugState: (state: boolean, name?: string) => dispatch(actions.updatePlugState(state, name)),
});

export default connect(mapStateToProps, mapDispatchToProps)(Remotes);
