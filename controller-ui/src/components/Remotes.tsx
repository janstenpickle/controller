import * as React from "react";
import MaterialIcon from "@material/react-material-icon";
import ReactModal from "react-modal";
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
import { Cascader } from "antd";
import Select, { Option } from "@material/react-select";

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

const customStyles = {
  content: {
    top: "50%",
    left: "50%",
    right: "auto",
    bottom: "auto",
    marginRight: "-50%",
    transform: "translate(-50%, -50%)",
    padding: "5px",
    background: "#f2f2f2",
  },
  overlay: {
    zIndex: 1
  }
};

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
    button: undefined
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
            `${window.location.protocol}//${window.location.hostname}:8090/config/remote/${data.name}`,
            { method: "PUT", body: JSON.stringify(updatedRemote) }
          ).then(res => {
            if (res.ok) {
              finishEdit();
              this.props.updateRemote(updatedRemote);
            } else {
              alert("Failed to update remote");
            }
          });
        }
      };

      return (
        <div className="mdc-card mdc-card--background" key={data.name}>
          <div hidden={this.state.remoteEditMode.get(data.name) || false}>
            <div className="center-align mdc-typography mdc-typography--overline">
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
                id="add-remote-button"
                className="icon material-icons button-add"
                onClick={() =>
                  loadData().then(loaded =>
                    this.setState({
                      buttonAddMode: true,
                      buttonRemote: data,
                      macros: loaded[0],
                      remoteCommands: loaded[1],
                      switches: loaded[2]
                    })
                  )
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

    const cascader = (placeholder: string) => {
      const remotes = new TSMap<string, TSMap<string, Set<string>>>();

      this.state.remoteCommands.forEach((rc: RemoteCommand) => {
        const remote =
          remotes.get(rc.remote) || new TSMap<string, Set<string>>();
        const commands = remote.get(rc.device) || new Set<string>();

        commands.add(rc.name);
        remote.set(rc.device, commands);
        remotes.set(rc.remote, remote);
      });

      const switches = new TSMap<string, Set<string>>();

      this.state.switches.forEach(s => {
        const device = switches.get(s.device) || new Set<string>();

        device.add(s.name);
        switches.set(s.device, device);
      });

      const opts = [
        {
          value: "macro",
          label: "Macro",
          children: this.state.macros.map((m: string) => ({
            value: m,
            label: m
          }))
        },
        {
          value: "switch",
          label: "Switch",
          children: switches.map((sws, device) => ({
            value: device,
            label: device,
            children: Array.from(sws.values()).map(s => ({
              value: s,
              label: s
            }))
          }))
        },
        {
          value: "remote",
          label: "Remote",
          children: remotes.map((devices, remote) => ({
            value: remote,
            label: remote,
            children: devices.map((commands, device) => ({
              value: device,
              label: device,
              children: Array.from(commands.values()).map(command => ({
                value: command,
                label: command
              }))
            }))
          }))
        }
      ];

      return (
        <Cascader
          options={opts}
          onChange={buttonActionChange}
          placeholder={placeholder}
        />
      );
    };

    const buttonActionChange = (data: string[]) => {
      const button = this.state.button;

      if (data[0] === "remote" && button) {
        switch (button.renderTag) {
          case "icon":
            return this.setState({
              button: {
                ...button,
                tag: "remote",
                renderTag: "icon",
                remote: data[1],
                device: data[2],
                name: data[3]
              }
            });
          case "label":
            return this.setState({
              button: {
                ...button,
                tag: "remote",
                renderTag: "label",
                remote: data[1],
                device: data[2],
                name: data[3]
              }
            });
        }
      } else if (data[0] === "switch" && button) {
        switch (button.renderTag) {
          case "icon":
            return this.setState({
              button: {
                ...button,
                tag: "switch",
                renderTag: "icon",
                device: data[1],
                name: data[2],
                isOn: false
              }
            });
          case "label":
            return this.setState({
              button: {
                ...button,
                tag: "switch",
                renderTag: "label",
                device: data[1],
                name: data[2],
                isOn: false
              }
            });
        }
      } else if (data[0] === "macro" && button) {
        switch (button.renderTag) {
          case "icon":
            return this.setState({
              button: {
                ...button,
                tag: "macro",
                renderTag: "icon",
                name: data[1],
                isOn: false
              }
            });
          case "label":
            return this.setState({
              button: {
                ...button,
                tag: "macro",
                renderTag: "label",
                name: data[1],
                isOn: false
              }
            });
        }
      } else {
      }
    };

    const editLabelOrIcon = () => {
      const button = this.state.button;

      if (button) {
        switch (button.renderTag) {
          case "label":
            return (
              <TextField label="Button Label">
                <Input
                  value={button.label}
                  onChange={(e: React.FormEvent<HTMLInputElement>) => {
                    button.label = e.currentTarget.value;
                    this.setState({ button: button });
                  }}
                />
              </TextField>
            );
          case "icon":
            return (
              <TextField label="Button Icon">
                <Input
                  value={button.icon}
                  onChange={(e: React.FormEvent<HTMLInputElement>) => {
                    button.icon = e.currentTarget.value;
                    this.setState({ button: button });
                  }}
                />
              </TextField>
            );
          default:
            return <React.Fragment></React.Fragment>;
        }
      } else {
        return <React.Fragment></React.Fragment>;
      }
    };

    const buttonSubmit = () => {
      const remote = this.state.buttonRemote;
      const button = this.state.button;
      if (remote && button) {
        const buttons = remote.buttons.filter(
          (_, i) => !(i === this.state.buttonIndex)
        );
        buttons.push(button);
        remote.buttons = buttons;

        fetch(
          `${window.location.protocol}//${window.location.hostname}:8090/config/remote/${remote.name}`,
          { method: "PUT", body: JSON.stringify(remote) }
        ).then(res => {
          if (res.ok) {
            this.setState(this.defaultState);
            this.props.updateRemote(remote);
          } else {
            alert("Failed to update remote");
          }
        });
      } else {
        this.setState(this.defaultState);
      }
    };

    const editButton = () => {
      return (
        <React.Fragment>
          <div className="center-align mdc-typography mdc-typography--overline">
            Edit Button
          </div>

          {editLabelOrIcon()}

          <br />
          {cascader("")}
          <br />

          <button
            onClick={() => this.setState(this.defaultState)}
            className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--outlined mdc-elevation--z2"
          >
            Cancel
          </button>

          <button
            className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--raised"
            onClick={buttonSubmit}
          >
            Submit
          </button>
        </React.Fragment>
      );
    };

    const addButton = () => {
      const remote = this.state.buttonRemote;

      const appearAfter = () => {
        if (remote) {
          const button = remote.buttons[this.state.buttonIndex]
          if (button) {
            return button.name
          } else {
            return undefined
          }
        } else {
          return undefined
        }
      }

      const currentValue = () => {
        const button = this.state.button;
        if (button) {
          return button.renderTag.valueOf();
        } else {
          return "";
        }
      };

      const others = () => {
        if (remote) {
          const options = remote.buttons.map((button, i) => (
            <Option value={i}>{button.name}</Option>
          ));
          options.push(<Option disabled={true}></Option>);
          return options;
        } else {
          return [<React.Fragment></React.Fragment>];
        }
      };

      return (
        <React.Fragment>
          <div className="center-align mdc-typography mdc-typography--overline">
            Add Button
          </div>

          <Select
            label="Button Type"
            value={currentValue()}
            onChange={(e: React.ChangeEvent<HTMLSelectElement>) => {
              if (e.currentTarget.value === "icon") {
                this.setState({
                  button: {
                    tag: "macro",
                    renderTag: "icon",
                    name: "",
                    icon: ""
                  }
                });
              } else if (e.currentTarget.value === "label") {
                this.setState({
                  button: {
                    tag: "macro",
                    renderTag: "label",
                    name: "",
                    label: ""
                  }
                });
              }
            }}
          >
            <Option disabled={true}></Option>
            <Option value={"icon"}>Icon</Option>
            <Option value={"label"}>Label</Option>
          </Select>

          <br />
          {editLabelOrIcon()}

          <br />

          <Select
            label="Add after"
            value={appearAfter()}
            onChange={(e: React.ChangeEvent<HTMLSelectElement>) => {
              this.setState({
                buttonIndex: Number(e.currentTarget.value)
              })
            }}
          >
            {others()}
          </Select>

          <br />
          {cascader("")}
          <br />

          <button
            onClick={() => this.setState(this.defaultState)}
            className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--outlined mdc-elevation--z2"
          >
            Cancel
          </button>

          <button
            className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--raised"
            onClick={buttonSubmit}
          >
            Submit
          </button>
        </React.Fragment>
      );
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

        <ReactModal
          isOpen={this.state.buttonAddMode || this.state.buttonEditMode}
          shouldCloseOnEsc={true}
          onRequestClose={() => this.setState(this.defaultState)}
          style={customStyles}
        >
          {modalContent()}
        </ReactModal>
      </React.Fragment>
    );
  }
}
