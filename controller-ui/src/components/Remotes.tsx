import * as React from "react";
import MaterialIcon from "@material/react-material-icon";
import TextField, { Input } from "@material/react-text-field";
import { Layout, Layouts, Responsive, WidthProvider } from "react-grid-layout";
import {
  RemoteButtons,
  RemoteCommand,
  RemoteData,
  Switch
} from "../types/index";
import { renderButton } from "./MainButtons";
import { TSMap } from "typescript-map";
import { macrosAPI } from "../api/macros";
import { remoteCommandsAPI } from "../api/remotecontrol";
import { switchesApi } from "../api/switch";
import AddButtonDialog from "./AddButtonDialog";
import EditButtonDialog from "./EditButtonDialog";
import Alert from "./Alert";
import Confirmation from "./Confirmation";
import { buttonSubmit, updateRemote } from "../common/ButtonOps";
import { baseURL } from '../common/Api';

const ResponsiveReactGridLayout = WidthProvider(Responsive);

export interface Props {
  remotes: RemoteData[];
  currentRoom: string;
  focus: (remote: string) => void;
  focusedRemote: string;
  fetchRemotes(): void;
  plugState(state: boolean, name?: string): void;
  showAll: boolean;
  updateRemote(remote: RemoteData): void;
  editMode: boolean;
}

export interface Coords {
  x: number;
  y: number;
}

interface RemotePlacement {
  name: string;
  lg?: Coords;
  md?: Coords;
  sm?: Coords;
  xs?: Coords;
  xxs?: Coords;
}

interface RemoteCoords {
  coords: TSMap<string, RemotePlacement>;
  next: Coords;
}

interface RemotesState {
  remoteEditMode: TSMap<string, boolean>;
  remoteLabel?: string;
  buttonAddMode: boolean;
  buttonEditMode: boolean;
  button?: RemoteButtons;
  buttonIndex: number;
  buttonRemote?: RemoteData;
  buttonRemoteKey?: string;
  macros: string[];
  remoteCommands: RemoteCommand[];
  switches: Switch[];
  alertOpen: boolean;
  alertMessage?: string;
  confirmOpen: boolean;
  remoteName?: string;
}

export default class Remotes extends React.Component<Props, RemotesState> {
  public componentDidMount() {
    this.props.fetchRemotes();
  }

  private defaultState: RemotesState = {
    remoteEditMode: new TSMap<string, boolean>(),
    buttonAddMode: false,
    buttonEditMode: false,
    remoteCommands: [],
    macros: [],
    switches: [],
    buttonIndex: -1,
    button: undefined,
    alertOpen: false,
    confirmOpen: false,
    remoteName: undefined
  };

  state = this.defaultState;

  public render() {
    const loadData: () => Promise<[string[], RemoteCommand[], Switch[]]> = () =>
      macrosAPI
        .fetchMacrosAsync()
        .then(macros =>
          switchesApi
            .fetchSwitchesAsync()
            .then(switches =>
              remoteCommandsAPI
                .fetchRemoteCommandsAsync()
                .then(remoteCommands => [macros, remoteCommands, switches])
            )
        );

      const deleteRemote = () => {
          const remote = this.state.remoteName;
          this.setState(this.defaultState);
          if (remote) {
            fetch(
              `${baseURL}/config/remote/${remote}`,
              { method: "DELETE" }
            ).then(res => {
              if (!res.ok) {
                res.text().then(text => alert(`Failed to delete remote: ${text}`));
              }
            });
          }
        };

    const alert = (message: string) =>
      this.setState({ alertMessage: message, alertOpen: true });

    const filteredRemotes = this.props.remotes.filter(
      (data: RemoteData) =>
        (data.isActive || this.props.showAll) &&
        (data.rooms.length === 0 || data.rooms.includes(this.props.currentRoom))
    );

    const remotePlacements: RemotePlacement[] = filteredRemotes.map(
      (data: RemoteData) => {
        return { name: data.name };
      }
    );

    const herp = (remoteData: RemoteData, i: number) => (
      button: RemoteButtons
    ) => {
      if (remoteData.editable) {
        loadData().then(data =>
          this.setState({
            button: button,
            buttonIndex: i,
            buttonRemote: remoteData,
            buttonEditMode: true,
            macros: data[0],
            remoteCommands: data[1],
            switches: data[2]
          })
        );
      }
    };

    const divs = filteredRemotes.map((data: RemoteData) => {
      const buttons = data.buttons.map((button: RemoteButtons, i: number) =>
        renderButton(
          button,
          this.props.currentRoom,
          this.props.plugState,
          this.props.editMode,
          herp(data, i)
        )
      );

      const editMode = () =>
        this.setState({
          remoteEditMode: this.state.remoteEditMode.set(data.name, true),
          remoteLabel: data.label
        });

      const finishEdit = () =>
        this.setState({
          remoteEditMode: this.state.remoteEditMode.set(data.name, false)
        });

      const update = () => {
        const updatedRemote = {
          ...data,
          label: this.state.remoteLabel || data.label
        };

        if (updatedRemote === data) {
          finishEdit();
        } else {
          fetch(
            `${baseURL}/config/remote/${data.name}`,
            { method: "PUT", body: JSON.stringify(updatedRemote) }
          ).then(res => {
            if (res.ok) {
              finishEdit();
              this.props.updateRemote(updatedRemote);
            } else {
              res
                .text()
                .then(text => alert(`Failed to update remote: ${text}`));
            }
          });
        }
      };

      return (
        <div className="mdc-card mdc-card--background" key={data.name}>
          <div hidden={this.state.remoteEditMode.get(data.name) || false}>
            <div className="center-align mdc-typography mdc-typography--overline mdc-theme--on-surface">
              {data.label}
              <i
                id="edit-remote-name"
                className="icon material-icons remote-edit"
                onClick={editMode}
                hidden={!(this.props.editMode && data.editable)}
              >
                edit
              </i>
            </div>

            <div className="center-align">{buttons}</div>
            <div>
              <i
                className="icon material-icons button-delete"
                onClick={() =>
                  this.setState({
                    confirmOpen: true,
                    remoteName: data.name
                  })
                }
                hidden={!(this.props.editMode && data.editable)}
              >
                clear
              </i>
              <i
                id="add-remote-button"
                className="icon material-icons button-add mdc-theme--on-surface"
                onClick={() =>
                  this.setState({
                    buttonAddMode: true,
                    buttonRemote: data
                  })
                }
                hidden={!(this.props.editMode && data.editable)}
              >
                add
              </i>
            </div>
          </div>
          <div hidden={!(this.state.remoteEditMode.get(data.name) || false)}>
            <div className="center-align">
              <TextField
                trailingIcon={<MaterialIcon role="button" icon="check" />}
                onTrailingIconSelect={update}
              >
                <Input
                  value={this.state.remoteLabel}
                  onChange={(e: React.FormEvent<HTMLInputElement>) =>
                    this.setState({ remoteLabel: e.currentTarget.value })
                  }
                />
              </TextField>
            </div>
            <div className="center-align">{buttons}</div>
          </div>
        </div>
      );
    });

    const nextCoords: (cols: number, coords: Coords) => Coords = (
      cols: number,
      coords: Coords
    ) => {
      if (coords.x < cols - 1) {
        return { x: coords.x + 1, y: coords.y };
      } else {
        return { x: 0, y: coords.y + 1 };
      }
    };

    const cToString = (coords: Coords) =>
      coords.x.toString() + coords.y.toString();

    const placement: (
      cols: number,
      rs: RemotePlacement[],
      selector: (data: RemotePlacement) => Coords | undefined,
      setter: (coords: Coords, data: RemotePlacement) => RemotePlacement
    ) => RemotePlacement[] = (
      cols: number,
      rs: RemotePlacement[],
      selector: (data: RemotePlacement) => Coords | undefined,
      setter: (coords: Coords, data: RemotePlacement) => RemotePlacement
    ) =>
      rs
        .reduce(
          (a: RemoteCoords, data: RemotePlacement) => {
            const coords = selector(data) || { x: 0, y: 0 };
            if (a.coords.has(cToString(coords)) || coords.x >= cols - 1) {
              const next = a.coords
                .keys()
                .sort()
                .reduce((co: Coords, s: string) => {
                  if (s === cToString(co)) {
                    return nextCoords(cols, co);
                  } else {
                    return co;
                  }
                }, a.next);
              return {
                coords: a.coords.set(cToString(next), setter(next, data)),
                next: nextCoords(cols, next)
              };
            } else {
              return {
                coords: a.coords.set(cToString(coords), setter(coords, data)),
                next: a.next
              };
            }
          },
          { coords: new TSMap<string, RemoteData>(), next: { x: 0, y: 0 } }
        )
        .coords.values();

    const layout: (
      cols: number,
      selector: (data: RemotePlacement) => Coords | undefined,
      setter: (coords: Coords, data: RemotePlacement) => RemotePlacement
    ) => Layout[] = (
      cols: number,
      selector: (data: RemotePlacement) => Coords | undefined,
      setter: (coords: Coords, data: RemotePlacement) => RemotePlacement
    ) =>
      placement(cols, remotePlacements, selector, setter).map(
        (data: RemotePlacement) => {
          const coords = selector(data) || { x: 0, y: 0 };
          return {
            i: data.name,
            x: coords.x,
            y: coords.y,
            w: 1,
            h: 1,
            isResizable: false,
            isDraggable: false
          };
        }
      );

    const layouts: Layouts = {
      lg: layout(
        4,
        (data: RemotePlacement) => data.lg,
        (coords: Coords, data: RemotePlacement) => {
          data.lg = coords;
          return data;
        }
      ),
      md: layout(
        3,
        (data: RemotePlacement) => data.md,
        (coords: Coords, data: RemotePlacement) => {
          data.md = coords;
          return data;
        }
      ),
      sm: layout(
        2,
        (data: RemotePlacement) => data.sm,
        (coords: Coords, data: RemotePlacement) => {
          data.sm = coords;
          return data;
        }
      ),
      xs: layout(
        2,
        (data: RemotePlacement) => data.xs,
        (coords: Coords, data: RemotePlacement) => {
          data.xs = coords;
          return data;
        }
      ),
      xxs: layout(
        1,
        (data: RemotePlacement) => data.xxs,
        (coords: Coords, data: RemotePlacement) => {
          data.xxs = coords;
          return data;
        }
      )
    };

    const cols = { lg: 4, md: 3, sm: 2, xs: 2, xxs: 1 };

    const layoutChange: (layout: Layout[], layouts: Layouts) => void = (
      layout: Layout[],
      layouts: Layouts
    ) => console.log(layouts);

    const editButton = () => {
      const remote = this.state.buttonRemote;
      const button = this.state.button;

      if (remote && button) {
        const deleteButton = () => {
          const buttons = remote.buttons.filter(b => b.name !== button.name);
          const r: RemoteData = { ...remote, buttons: buttons };
          updateRemote(r, r => {
            this.setState(this.defaultState);
            this.props.updateRemote(r);
          });
        };

        return (
          <EditButtonDialog
            isOpen={this.state.buttonEditMode}
            onRequestClose={() => this.setState(this.defaultState)}
            button={button}
            onSuccess={(b: RemoteButtons) => {
              this.setState(this.defaultState);
              buttonSubmit(
                remote,
                b,
                remote.buttons.indexOf(button),
                this.props.updateRemote,
                true
              );
            }}
            onDelete={deleteButton}
          ></EditButtonDialog>
        );
      } else {
        return <React.Fragment></React.Fragment>;
      }
    };

    const addButton = () => {
      const remote = this.state.buttonRemote;

      if (remote) {
        return (
          <AddButtonDialog
            isOpen={this.state.buttonAddMode}
            onRequestClose={() => this.setState(this.defaultState)}
            buttons={remote.buttons.map(button => button.name)}
            onSuccess={(button: RemoteButtons, index: number) => {
              this.setState(this.defaultState);
              buttonSubmit(
                remote,
                button,
                index,
                this.props.updateRemote,
                false
              );
            }}
          ></AddButtonDialog>
        );
      } else {
        return <React.Fragment></React.Fragment>;
      }
    };

    const modalContent = () => {
      if (this.state.buttonEditMode) {
        return editButton();
      } else if (this.state.buttonAddMode) {
        return addButton();
      } else {
        return <React.Fragment></React.Fragment>;
      }
    };

    return (
      <React.Fragment>
        <Alert
          isOpen={this.state.alertOpen}
          message={this.state.alertMessage}
          onClose={() => this.setState({ alertOpen: false })}
        ></Alert>
        <Confirmation
          isOpen={this.state.confirmOpen}
          message="Are you sure you want to delete this remote?"
          onCancel={() => this.setState({ 
            confirmOpen: false,
            remoteName: undefined
          })}
          onOk={() => {
            this.setState({ confirmOpen: false });
            deleteRemote();
          }}
        ></Confirmation>
        <ResponsiveReactGridLayout
          className="layout"
          rowHeight={280}
          cols={cols}
          layouts={layouts}
          containerPadding={[5, 5]}
          compactType="vertical"
          onLayoutChange={layoutChange}
        >
          {divs}
        </ResponsiveReactGridLayout>

        {modalContent()}
      </React.Fragment>
    );
  }
}
