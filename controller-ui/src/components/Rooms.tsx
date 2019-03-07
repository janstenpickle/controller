import * as React from 'react';

interface Props {
  rooms: string[],
  setRoom: (room: string) => void,
  fetchRooms: () => void
}

export const toTitleCase = (room: string) => {
    var str = room.toLowerCase().split('_');
    for (var i = 0; i < str.length; i++) {
        str[i] = str[i].charAt(0).toUpperCase() + str[i].slice(1);
    }
    return str.join(' ');
};

export default class Rooms extends React.Component<Props,{}> {
  public componentDidMount() {
    this.props.fetchRooms();
  }

  public render() {
    const setRoom = (room: string) => () => this.props.setRoom(room)

    const listItem = (room: string) => 
        (<li className="mdl-menu__item" key={room} onClick={setRoom(room)}>
          {toTitleCase(room)}
        </li>)

    const button = () => {
        const rooms = this.props.rooms || []

        return(
            <div>
                <button id="rooms-menu" className="mdl-button mdl-js-button mdl-button--icon">
                    <i className="material-icons">more_vert</i>
                </button>
                <ul className="mdl-menu mdl-menu--bottom-left mdl-js-menu mdl-js-ripple-effect" data-mdl-for="rooms-menu" >
                    {rooms.map(listItem)}
                </ul>
            </div>
        );
      };

    return(button());
  }
};
