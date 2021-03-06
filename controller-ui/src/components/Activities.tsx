import MaterialIcon from "@material/react-material-icon";
import TextField, { Input } from "@material/react-text-field";
import * as React from "react";
import ReactModal from "react-modal";
import { TSMap } from "typescript-map";
import { macrosAPI } from "../api/macros";
import { remoteCommandsAPI } from "../api/remotecontrol";
import { ActivityData, ContextButtons, RemoteCommand } from "../types/index";
import { Cascader } from "antd";
import Form from "./Form";
import { baseURL } from "../common/Api";

interface Props {
  activities: TSMap<string, ActivityData>;
  currentRoom: string;
  currentActivity: string;
  activate: (room: string, activity: string) => void;
  fetchActivities(): void;
  editMode: boolean;
}

export function renderButton(
  key: string,
  actvity: ActivityData,
  currentActivity: string,
  currentRoom: string,
  activate: (room: string, key: string, activity: ActivityData) => void
) {
  const baseClass =
    "mdc-ripple-upgraded mdc-button mdc-button--dense mdc-elevation--z2";

  const colored = (activityName: string) => {
    if (activityName === currentActivity) {
      return baseClass + " mdc-button--raised";
    } else {
      return baseClass + " mdc-button--outlined mdc-theme--on-secondary";
    }
  };

  return (
    <div key={actvity.name} className="button">
      <button
        className={colored(actvity.name)}
        onClick={() => activate(currentRoom, key, actvity)}
      >
        {actvity.label || actvity.name}
      </button>
    </div>
  );
}

interface DialogState {
  isOpen: boolean;
  label?: string;
  modalLabel: string;
  macros: string[];
  remoteCommands: RemoteCommand[];
  contextButtons: ContextButtons[];
  submit(): void;
}

export default class Activities extends React.Component<Props, DialogState> {
  public componentDidMount() {
    ReactModal.setAppElement(document.getElementById("dialog") as HTMLElement);
    this.props.fetchActivities();
  }

  private handleSubmit = () => {
    this.setState(this.defaultState);
  };

  private defaultState: DialogState = {
    isOpen: false,
    modalLabel: "Add Activity",
    submit: this.handleSubmit,
    macros: [],
    remoteCommands: [],
    contextButtons: [],
    label: undefined
  };

  state = this.defaultState;

  public render() {
    const loadData: () => Promise<[string[], RemoteCommand[]]> = () =>
      macrosAPI
        .fetchMacrosAsync()
        .then((macros: string[]) =>
          remoteCommandsAPI
            .fetchRemoteCommandsAsync()
            .then((remoteCommands: RemoteCommand[]) => [macros, remoteCommands])
        );

    const buttonAction = (
      room: string,
      key: string,
      activity: ActivityData
    ) => {
      if (this.props.editMode && activity.editable) {
        const submit = () => {
          fetch(
            `${baseURL}/config/activity/${key}`,
            {
              method: "PUT",
              body: JSON.stringify({
                ...activity,
                label: this.state.label || activity.label
              })
            }
          );
          this.setState(this.defaultState);
        };

        loadData().then(data =>
          this.setState({
            isOpen: true,
            modalLabel: "Edit Activity",
            label: activity.label,
            submit: submit,
            macros: data[0],
            remoteCommands: data[1],
            contextButtons: activity.contextButtons
          })
        );
      } else if (this.props.editMode && !activity.editable) {
      } else {
        fetch(
          `${baseURL}/control/activity/${room}`,
          { method: "POST", body: activity.name }
        ).then(_ => this.props.activate(room, activity.name));
      }
    };

    const renderedButtons = this.props.activities
      .filter(actvitiy => (actvitiy.room || "") === this.props.currentRoom)
      .map((actvitiy, key) => {
        if (key) {
          return renderButton(
            key,
            actvitiy,
            this.props.currentActivity,
            this.props.currentRoom,
            buttonAction
          );
        } else {
          return <React.Fragment></React.Fragment>;
        }
      });

    const addButton = () => {
      if (this.props.editMode) {
        return (
          <div>
            <button
              className="'mdc-ripple-upgraded mdc-fab mdc-fab--small mdc-button--raised"
              onClick={openDialog}
            >
              <span className="mdc-fab__icon material-icons">add</span>
            </button>
          </div>
        );
      } else {
        return <React.Fragment></React.Fragment>;
      }
    };

    const openDialog = () => {
      loadData().then((data: [string[], RemoteCommand[]]) =>
        this.setState({
          isOpen: true,
          macros: data[0],
          remoteCommands: data[1]
        })
      );
    };

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
          className={"controller-width-class"}
          options={opts}
          onChange={onChange}
          placeholder={placeholder}
        />
      );
    };

    function onChange(value: any) {
      console.log(value);
    }

    const currentContextButtons = () => {
      return this.state.contextButtons.map((button: ContextButtons) => {
        const command = () => {
          switch (button.tag) {
            case "macro":
              return `Macro / ${button.name}`;
            case "remote":
              return `Remote / ${button.remote} / ${button.device} / ${button.command}`;
          }
        };

        return (
          <React.Fragment key={button.name}>
            {cascader(command())}
            <br />
          </React.Fragment>
        );
      });
    };

    const elements = new TSMap<string, JSX.Element | JSX.Element[]>()
      .set(
        "Activity Name",
        <TextField
          label="Activity Name"
          outlined={true}
          dense={true}
          onTrailingIconSelect={() => this.setState({ label: "" })}
          trailingIcon={<MaterialIcon role="button" icon="delete" />}
        >
          <Input
            value={this.state.label}
            onChange={(e: React.FormEvent<HTMLInputElement>) =>
              this.setState({ label: e.currentTarget.value })
            }
          />
        </TextField>
      )
      .set("Context Buttons", currentContextButtons())
      .set("Action", cascader(""));

    return (
      <div className="center-align mdl-cell--12-col">
        {renderedButtons}
        {addButton()}
        <Form
          name={this.state.modalLabel}
          isOpen={this.state.isOpen}
          onCancel={() => this.setState(this.defaultState)}
          onSubmit={this.state.submit}
          elements={elements}
        ></Form>
      </div>
    );
  }
}
