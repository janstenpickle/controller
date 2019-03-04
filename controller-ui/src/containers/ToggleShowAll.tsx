import ToggleShowAll from '../components/ToggleShowAll';
import { StoreState } from '../types/index';
import * as actions from '../actions/';
import { connect } from 'react-redux';


export function mapStateToProps(state: StoreState) {
  return {
    showAll: state.showAll
  };
}

const mapDispatchToProps = (dispatch: any) => ({
  toggle: () => dispatch(actions.toggleShowAll()),
});

export default connect(mapStateToProps, mapDispatchToProps)(ToggleShowAll);
