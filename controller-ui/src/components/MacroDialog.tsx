import * as React from "react";
import { TSMap } from "typescript-map";
import Form from "./Form";
import TextField, { Input } from "@material/react-text-field";
import MaterialIcon from "@material/react-material-icon";
import { MacroCommand, MacroSleep, MacroRemote } from "../types";
import { Cell, Row } from "@material/react-layout-grid";
import Select, { Option } from "@material/react-select";
import {
  RemoteCommand,
  Switch,
  MacroMacro,
  MacroToggleSwitch
} from "../types/index";
import { macrosAPI } from "../api/macros";
import { remoteCommandsAPI } from "../api/remotecontrol";
import { switchesApi } from "../api/switch";
import { CascaderOptionType } from "antd/lib/cascader";
import { Cascader } from "antd";
import { MacroSwitchOn, MacroSwitchOff } from "../types/index";
import Alert from './Alert';
import { baseURL } from "../common/Api";

interface Props {
  isOpen: boolean;
  onRequestClose: () => void;
}

interface State {
  name?: string;
  commands: MacroCommand[];
  addCommand: boolean;
  commandType?: string;
  sleepCommand?: MacroSleep;
  macroCommand?: MacroMacro;
  toggleSwitchCommand?: MacroToggleSwitch;
  switchOnCommand?: MacroSwitchOn;
  switchOffCommand?: MacroSwitchOff;
  remoteCommand?: MacroRemote;
  macros: string[];
  remoteCommands: RemoteCommand[];
  switches: Switch[];
  alertOpen: boolean;
  alertMessage?: string;
}

export default class MacroDialog extends React.Component<Props, State> {
  private defaultState: State = {
    commandType: undefined,
    sleepCommand: undefined,
    macroCommand: undefined,
    toggleSwitchCommand: undefined,
    switchOnCommand: undefined,
    switchOffCommand: undefined,
    remoteCommand: undefined,
    commands: [],
    addCommand: false,
    macros: [],
    remoteCommands: [],
    switches: [],
    alertOpen: false
  };

  state: State = this.defaultState;

  public render() {
    const defaultState: State = {
      ...this.defaultState,
      commands: this.state.commands,
      macros: this.state.macros,
      remoteCommands: this.state.remoteCommands,
      switches: this.state.switches
    };

    const loadData = () => {
      macrosAPI
        .fetchMacrosAsync()
        .then(macros =>
          switchesApi
            .fetchSwitchesAsync()
            .then(switches =>
              remoteCommandsAPI
                .fetchRemoteCommandsAsync()
                .then(remoteCommands =>
                  this.setState({ macros, switches, remoteCommands })
                )
            )
        );
    };

    const alert = (message: string) =>
      this.setState({ alertMessage: message, alertOpen: true });

    const name = (
      <TextField
        label="Macro Name"
        outlined={true}
        dense={true}
        onTrailingIconSelect={() => this.setState({ name: "" })}
        trailingIcon={<MaterialIcon role="button" icon="delete" />}
      >
        <Input
          value={this.state.name}
          onChange={(e: React.FormEvent<HTMLInputElement>) =>
            this.setState({ name: e.currentTarget.value })
          }
        />
      </TextField>
    );

    const cascader = (
      options: CascaderOptionType[],
      onChange: (value: string[]) => void
    ) => (
      <Cascader
        className={"controller-width-class"}
        options={options}
        onChange={onChange}
      />
    );

    const switchSelect = (onChange: (device: string, name: string) => void) => {
      const switches = new TSMap<string, Set<string>>();

      this.state.switches.forEach(s => {
        const device = switches.get(s.device) || new Set<string>();

        device.add(s.name);
        switches.set(s.device, device);
      });

      const opts = switches.map((sws, device) => ({
        value: device,
        label: device,
        children: Array.from(sws.values()).map(s => ({
          value: s,
          label: s
        }))
      }));

      return cascader(opts, value => onChange(value[0], value[1]));
    };

    const commandType = (
      <Select
        label="Command Type"
        outlined={true}
        value={this.state.commandType}
        selectClassName={"controller-width-class"}
        onChange={(e: React.ChangeEvent<HTMLSelectElement>) =>
          this.setState({ commandType: e.currentTarget.value })
        }
      >
        <Option disabled={true}></Option>
        <Option value={"sleep"}>Sleep</Option>
        <Option value={"toggleSwitch"}>Toggle Switch</Option>
        <Option value={"switchOn"}>Switch On</Option>
        <Option value={"switchOff"}>Switch Off</Option>
        <Option value={"remote"}>Remote</Option>
        <Option value={"macro"}>Macro</Option>
      </Select>
    );

    const sleepField = () => {
      const sleepValue = () => {
        if (this.state.sleepCommand) {
          return this.state.sleepCommand.millis;
        } else {
          return 0;
        }
      };

      return (
        <TextField
          label="Sleep Millis"
          type="number"
          outlined={true}
          dense={true}
          onTrailingIconSelect={() =>
            this.setState({ sleepCommand: undefined })
          }
          trailingIcon={<MaterialIcon role="button" icon="delete" />}
        >
          <Input
            value={sleepValue()}
            onChange={(e: React.FormEvent<HTMLInputElement>) => {
              // TODO properly parse number
              this.setState({
                sleepCommand: {
                  type: "Sleep",
                  millis: Number(e.currentTarget.value)
                }
              });
            }}
          />
        </TextField>
      );
    };

    const macroField = () => {
      const macroValue = () => {
        if (this.state.macroCommand) {
          return this.state.macroCommand.name;
        } else {
          return undefined;
        }
      };

      const macroOptions = () => {
        const options = this.state.macros.map(macro => (
          <Option value={macro} key={macro}>
            {macro}
          </Option>
        ));

        options.push(<Option disabled={true} key=""></Option>);

        return options;
      };

      return (
        <Select
          label="Macro"
          value={macroValue()}
          outlined={true}
          selectClassName={"controller-width-class"}
          onChange={(e: React.ChangeEvent<HTMLSelectElement>) =>
            this.setState({
              macroCommand: {
                type: "Macro",
                name: e.currentTarget.value
              }
            })
          }
        >
          {macroOptions()}
        </Select>
      );
    };

    const toggleSwitchField = switchSelect((device, name) =>
      this.setState({
        toggleSwitchCommand: { type: "ToggleSwitch", device, name }
      })
    );

    const switchOnField = switchSelect((device, name) =>
      this.setState({ switchOnCommand: { type: "SwitchOn", device, name } })
    );

    const switchOffField = switchSelect((device, name) =>
      this.setState({ switchOffCommand: { type: "SwitchOff", device, name } })
    );

    const remoteField = () => {
      const remotes = new TSMap<string, TSMap<string, Set<string>>>();

      this.state.remoteCommands.forEach((rc: RemoteCommand) => {
        const remote =
          remotes.get(rc.remote) || new TSMap<string, Set<string>>();
        const commands = remote.get(rc.device) || new Set<string>();

        commands.add(rc.name);
        remote.set(rc.device, commands);
        remotes.set(rc.remote, remote);
      });

      const opts = remotes.map((devices, remote) => ({
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
      }));

      return cascader(opts, values =>
        this.setState({
          remoteCommand: {
            type: "Remote",
            remote: values[0],
            device: values[1],
            command: values[2]
          }
        })
      );
    };

    const submitCommand = (command?: MacroCommand) => {
      if (command) {
        const commands = this.state.commands;
        commands.push(command);
        this.setState({ commands });
      }
      this.setState(defaultState);
    };

    const commandSwitch = () => {
      switch (this.state.commandType) {
        case "sleep":
          return sleepField();
        case "macro":
          return macroField();
        case "toggleSwitch":
          return toggleSwitchField;
        case "switchOn":
          return switchOnField;
        case "switchOff":
          return switchOffField;
        case "remote":
          return remoteField();
        default:
          return undefined;
      }
    };

    const addCommandSwitch = () => {
      switch (this.state.commandType) {
        case "sleep":
          return submitCommand(this.state.sleepCommand);
        case "macro":
          return submitCommand(this.state.macroCommand);
        case "toggleSwitch":
          return submitCommand(this.state.toggleSwitchCommand);
        case "switchOn":
          return submitCommand(this.state.switchOnCommand);
        case "switchOff":
          return submitCommand(this.state.switchOffCommand);
        case "remote":
          return submitCommand(this.state.remoteCommand);
        default:
          return console.warn("Could not submit command");
      }
    };

    const addCommandElements = () => {
      const elements = new TSMap<string, JSX.Element | JSX.Element[]>().set(
        "Command Type",
        commandType
      );
      const command = commandSwitch();
      if (command) {
        elements.set("Command", command);
      }

      return elements;
    };

    const addCommand = () => {
      if (this.state.addCommand) {
        return (
          <Form
            name="Add Macro Command"
            isOpen={this.state.addCommand}
            onSubmit={addCommandSwitch}
            onCancel={() => this.setState(defaultState)}
            onAfterOpen={loadData}
            elements={addCommandElements()}
          ></Form>
        );
      } else {
        return (
          <Row>
            <Cell>
              <button
                className="'mdc-ripple-upgraded mdc-fab mdc-fab--small mdc-button--raised"
                onClick={() => this.setState({ addCommand: true })}
              >
                <span className="mdc-fab__icon material-icons">add</span>
              </button>
            </Cell>
          </Row>
        );
      }
    };

    const commandList = this.state.commands
      .map(command => {
        switch (command.type) {
          case "Sleep":
            return `Sleep for ${command.millis}ms`;
          case "Macro":
            return `Macro: '${command.name}'`;
          case "ToggleSwitch":
            return `Toggle Switch '${command.device}/${command.name}'`;
          case "SwitchOn":
            return `Switch On '${command.device}/${command.name}'`;
          case "SwitchOff":
            return `Switch Off '${command.device}/${command.name}'`;
          case "Remote":
            return `Send Remote Command '${command.remote}/${command.device}/${command.command}'`;
          default: 
            return "";
        }
      })
      .map((element, index) => (
        <React.Fragment key={index}>
          <i className="mdc-typography mdc-typography--overline">{element}</i>
          <button
            className="'mdc-ripple-upgraded mdc-fab mdc-fab--small mdc-fab__icon mdc-fab--color-override"
            onClick={() => {
              const commands = this.state.commands.filter(
                (_, idx) => idx !== index
              );
              this.setState({ commands });
            }}
          >
            <span className="mdc-fab__icon material-icons">clear</span>
          </button>
          <hr />
        </React.Fragment>
      ));

    const commands = () => {
      return (
        <React.Fragment>
          {commandList} {addCommand()}
        </React.Fragment>
      );
    };

    const elements = new TSMap<string, JSX.Element | JSX.Element[]>()
      .set("Macro Name", name)
      .set("Commands", commands());

    const submit = () => {
      if (this.state.name && this.state.commands.length > 0) {
        fetch(
          `${baseURL}/control/macro/submit/${this.state.name}`,
          { method: "POST", body: JSON.stringify(this.state.commands) }
        ).then(res => {
          if (res.ok) {
            this.props.onRequestClose();
          } else {
            res.text().then(text => alert(`Failed to create macro: ${text}`));
          }
        });
      } else if (!this.state.name) {
        alert("Please enter a name");
      } else {
        alert("Please add at least one command");
      }
    };

    return (
      <React.Fragment>
        <Alert
          isOpen={this.state.alertOpen}
          message={this.state.alertMessage}
          onClose={() => this.setState({ alertOpen: false })}
        ></Alert>
        <Form
          name="Create Macro"
          isOpen={this.props.isOpen}
          onSubmit={submit}
          onCancel={() => {
            this.setState(defaultState);
            this.props.onRequestClose();
          }}
          onAfterOpen={loadData}
          elements={elements}
        ></Form>
      </React.Fragment>
    );
  }
}
