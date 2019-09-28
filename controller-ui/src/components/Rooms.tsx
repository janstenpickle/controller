import * as React from "react";
import {
  MenuList,
  MenuListItem,
  MenuListItemText,
  MenuListDivider
} from "@material/react-menu";
import MenuSurface, { Corner } from "@material/react-menu-surface";
import { RemoteCommand } from "../types";
import Select, { Option } from "@material/react-select";
import ReactModal from "react-modal";
import TextField, { Input } from "@material/react-text-field";
import MaterialIcon from "@material/react-material-icon";
import { remoteCommandsAPI } from "../api/remotecontrol";
import Spinner from "react-spinner-material";

const customStyles = {
  content: {
    top: "50%",
    left: "50%",
    right: "auto",
    bottom: "auto",
    marginRight: "-50%",
    transform: "translate(-50%, -50%)",
    padding: "5px",
    background: "#f2f2f2"
  }
};

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
  remoteCommands: RemoteCommand[];
  remoteName?: string;
  deviceName?: string;
  commandName?: string;
  learnInProgress: boolean;
  learnSuccess: boolean;
  learnResponse?: string;
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
    remoteCommands: [],
    learnInProgress: false,
    learnSuccess: true
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

    const openLearnDialog = () => {
      remoteCommandsAPI
        .fetchRemoteCommandsAsync()
        .then((remoteCommands: RemoteCommand[]) =>
          this.setState({
            remoteCommands,
            menuOpen: false,
            remoteDialogOpen: true
          })
        );
    };

    const button = () => {
      const rooms = this.props.rooms || [];

      const setAnchorElement = (element: HTMLDivElement) => {
        if (this.state.anchorElement) {
          return;
        }
        this.setState({ anchorElement: element });
      };

      const handleLearn = () => {
        const remoteName = this.state.remoteName;
        const deviceName = this.state.deviceName;
        const commandName = this.state.commandName;

        const error = () => {
          alert("Please ensure form is filled");
        };

        if (remoteName && deviceName && commandName) {
          if (remoteName !== "" && deviceName !== "" && commandName !== "") {
            this.setState({ learnInProgress: true });

            fetch(
              `${window.location.protocol}//${window.location.hostname}:8090/control/remote/learn/${remoteName}/${deviceName}/${commandName}`,
              { method: "POST" }
            ).then(res => {
              res.text().then(body =>
                this.setState({
                  learnInProgress: false,
                  learnSuccess: res.ok,
                  learnResponse: body
                })
              );
            });
          } else {
            error();
          }
        } else {
          error();
        }
      };

      const learnForm = () => {
        const remotes = new Set(
          this.state.remoteCommands.map(rc => rc.remote)
        ).add("");

        return (
          <React.Fragment>
            <div className="center-align mdc-typography mdc-typography--overline">
              Learn Remote Command
            </div>
            <Select
              label="Remote Control"
              value={this.state.remoteName || ""}
              onChange={(e: React.ChangeEvent<HTMLSelectElement>) => {
                this.setState({
                  remoteName: e.currentTarget.value
                });
              }}
            >
              {Array.from(remotes).map(remote => (
                <Option key={remote} value={remote}>
                  {remote}
                </Option>
              ))}
            </Select>
            <br />
            <TextField
              label="Device Name"
              onTrailingIconSelect={() => this.setState({ deviceName: "" })}
              trailingIcon={<MaterialIcon role="button" icon="delete" />}
            >
              <Input
                value={this.state.deviceName}
                onChange={(e: React.FormEvent<HTMLInputElement>) =>
                  this.setState({ deviceName: e.currentTarget.value })
                }
              />
            </TextField>
            <br />
            <TextField
              label="Command Name"
              onTrailingIconSelect={() => this.setState({ commandName: "" })}
              trailingIcon={<MaterialIcon role="button" icon="delete" />}
            >
              <Input
                value={this.state.commandName}
                onChange={(e: React.FormEvent<HTMLInputElement>) =>
                  this.setState({ commandName: e.currentTarget.value })
                }
              />
            </TextField>
            <br />
            <button
              onClick={() => this.setState(this.defaultState)}
              className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--outlined mdc-elevation--z2"
            >
              Cancel
            </button>
            <button
              onClick={handleLearn}
              className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--raised"
            >
              Learn
            </button>
          </React.Fragment>
        );
      };

      const dialogLoading = () => {
        return (
          <React.Fragment>
            <div className="center-align mdc-typography mdc-typography--overline">
              Waiting for remote signal
            </div>
            <div className="center-align">
              <Spinner
                size={50}
                spinnerColor={"#333"}
                spinnerWidth={2}
                visible={true}
              />
            </div>
          </React.Fragment>
        );
      };

      const learnError = () => {
        return (
          <React.Fragment>
            <div className="center-align mdc-typography mdc-typography--overline">
              Failed to learn remote command
            </div>
            <div className="center-align mdc-typography mdc-typography--body1">
              {this.state.learnResponse}
            </div>

            <br />
            <button
              onClick={() => this.setState(this.defaultState)}
              className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--outlined mdc-elevation--z2"
            >
              Cancel
            </button>
            <button
              onClick={() =>
                this.setState({
                  ...this.defaultState,
                  remoteCommands: this.state.remoteCommands,
                  remoteDialogOpen: true
                })
              }
              className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--raised"
            >
              Back
            </button>
          </React.Fragment>
        );
      };

      const dialogContent = () => {
        if (!this.state.learnInProgress && !this.state.learnSuccess) {
          return learnError();
        } else if (!this.state.learnInProgress) {
          return learnForm();
        } else {
          return dialogLoading();
        }
      };

      const learnDialog = () => {
        return (
          <ReactModal
            isOpen={this.state.remoteDialogOpen}
            shouldCloseOnEsc={true}
            onRequestClose={() => this.setState(this.defaultState)}
            style={customStyles}
          >
            {dialogContent()}
          </ReactModal>
        );
      };

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
            className="mdc-icon-button material-icons"
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
                <MenuListItem key={index} onClick={setRoom(room)}>
                  <MenuListItemText primaryText={toTitleCase(room)} />
                </MenuListItem>
              ))}
              <MenuListDivider />
              <MenuListItem onClick={openLearnDialog}>
                Learn Remote Command
              </MenuListItem>
              <MenuListItem
                onClick={() => {
                  this.props.setEditMode(!this.props.editMode);
                  this.setState({ menuOpen: false });
                }}
              >
                <b>{editModeText()}</b>
              </MenuListItem>
              <MenuListDivider />
              <MenuListItem>
                <b>Settings</b>
              </MenuListItem>
            </MenuList>
          </MenuSurface>
          {learnDialog()}
        </div>
      );
    };

    return button();
  }
}
