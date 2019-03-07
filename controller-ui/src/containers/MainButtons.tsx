import MainButtons from '../components/MainButtons';
import { StoreState } from '../types/index';
import * as actions from '../actions/';
import { connect } from 'react-redux';


export function mapStateToProps(state: StoreState) {
  return {
    buttons: state.buttons,
    currentRoom: state.currentRoom
  };
}

const mapDispatchToProps = (dispatch: any) => ({
  fetchButtons: () => dispatch(actions.loadButtonsAction()),
  plugState: (state: boolean, name?: string) => dispatch(actions.updatePlugState(state, name)),
});

export default connect(mapStateToProps, mapDispatchToProps)(MainButtons);
