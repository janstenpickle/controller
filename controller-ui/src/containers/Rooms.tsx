import Rooms from '../components/Rooms';
import { StoreState } from '../types/index';
import * as actions from '../actions/';
import { connect } from 'react-redux';


export function mapStateToProps(state: StoreState) {
  return {
    rooms: state.rooms
  };
}

const mapDispatchToProps = (dispatch: any) => ({
  setRoom: (room: string) => dispatch(actions.setRoom(room)),
  fetchRooms: () => dispatch(actions.loadRoomsAction())
});

export default connect(mapStateToProps, mapDispatchToProps)(Rooms);
