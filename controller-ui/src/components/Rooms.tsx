import * as React from "react";
import {
  MenuList,
  MenuListItem,
  MenuListItemText,
  MenuListDivider
} from "@material/react-menu";
import MenuSurface, { Corner } from "@material/react-menu-surface";
import ReactModal from "react-modal";
import LearnRemoteCommandsDialog from "./LearnCommandDiaglog";
import MacroDialog from "./MacroDialog";

interface Props {
  rooms: string[];
  setRoom: (room: string) => void;
  fetchRooms: () => void;
  editMode: boolean;
  setEditMode: (editMode: boolean) => void;
}

interface MenuState {
  menuOpen: boolean;
  anchorElement?: HTMLDivElement;
  remoteDialogOpen: boolean;
  macroDialogOpen: boolean;
}

export const toTitleCase = (room: string) => {
  var str = room.toLowerCase().split("_");
  for (var i = 0; i < str.length; i++) {
    str[i] = str[i].charAt(0).toUpperCase() + str[i].slice(1);
  }
  return str.join(" ");
};

export default class Rooms extends React.Component<Props, MenuState> {
  defaultState: MenuState = {
    menuOpen: false,
    remoteDialogOpen: false,
    macroDialogOpen: false
  };

  state: MenuState = this.defaultState;

  public componentDidMount() {
    ReactModal.setAppElement(document.getElementById("dialog") as HTMLElement);
    this.props.fetchRooms();
  }

  onClose = () => {
    this.setState({ menuOpen: false });
  };

  public render() {
    const setRoom = (room: string) => () => {
      this.setState({ menuOpen: false });
      this.props.setRoom(room);
    };

    const button = () => {
      const rooms = this.props.rooms || [];

      const setAnchorElement = (element: HTMLDivElement) => {
        if (this.state.anchorElement) {
          return;
        }
        this.setState({ anchorElement: element });
      };

      const macroDialog = (
        <MacroDialog
          isOpen={this.state.macroDialogOpen}
          onRequestClose={() => this.setState(this.defaultState)}
        ></MacroDialog>
      );

      const learnDialog = (
        <LearnRemoteCommandsDialog
          isOpen={this.state.remoteDialogOpen}
          onRequestClose={() => this.setState(this.defaultState)}
        ></LearnRemoteCommandsDialog>
      );

      const editModeText = () => {
        if (this.props.editMode) {
          return "Disable Edit Mode";
        } else {
          return "Enable Edit Mode";
        }
      };

      return (
        <div className="mdc-menu-surface--anchor" ref={setAnchorElement}>
          <button
            id="rooms-menu"
            className="mdc-icon-button material-icons mdc-theme--on-surface"
            onClick={() => this.setState({ menuOpen: true })}
          >
            more_vert
          </button>
          <MenuSurface
            open={this.state.menuOpen}
            onClose={this.onClose}
            anchorCorner={Corner.BOTTOM_LEFT}
            anchorElement={this.state.anchorElement}
          >
            <MenuList>
              {rooms.map((room, index) => (
                <MenuListItem key={index} onClick={setRoom(room)} className="mdc-theme--on-surface">
                  <MenuListItemText primaryText={toTitleCase(room)} />
                </MenuListItem>
              ))}
              <MenuListDivider />
              <MenuListItem
                className="mdc-theme--on-surface"
                onClick={() =>
                  this.setState({ macroDialogOpen: true, menuOpen: false })
                }
              >
                Create Macro
              </MenuListItem>
              <MenuListItem
                className="mdc-theme--on-surface"
                onClick={() =>
                  this.setState({ remoteDialogOpen: true, menuOpen: false })
                }
              >
                Learn Remote Command
              </MenuListItem>
              <MenuListItem
                className="mdc-theme--on-surface"
                onClick={() => {
                  this.props.setEditMode(!this.props.editMode);
                  this.setState({ menuOpen: false });
                }}
              >
                <b>{editModeText()}</b>
              </MenuListItem>
              <MenuListDivider />
              <MenuListItem className="mdc-theme--on-surface">
                <b>Settings</b>
              </MenuListItem>
            </MenuList>
          </MenuSurface>
          {macroDialog}
          {learnDialog}
        </div>
      );
    };

    return button();
  }
}
