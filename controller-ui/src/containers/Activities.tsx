import Activities from '../components/Activities';
import { StoreState } from '../types/index';
import * as actions from '../actions/';
import { connect } from 'react-redux';


export function mapStateToProps(state: StoreState) {
  return {
    activities: state.activities,
    currentActivity: state.currentActivity.get(state.currentRoom) || '',
    currentRoom: state.currentRoom
  };
}

const mapDispatchToProps = (dispatch: any) => ({
  activate: (room: string, activity: string) => dispatch(actions.setActivity(room, activity)),
  fetchActivities: () => dispatch(actions.loadActivitiesAction()),
});

export default connect(mapStateToProps, mapDispatchToProps)(Activities);
