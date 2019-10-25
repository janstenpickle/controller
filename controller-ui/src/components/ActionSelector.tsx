import * as React from "react";
import { RemoteCommand, Switch, RemoteCommandSource } from "../types/index";
import { Cascader } from "antd";
import { macrosAPI } from "../api/macros";
import { remoteCommandsAPI } from "../api/remotecontrol";
import { switchesApi } from "../api/switch";
import { TSMap } from "typescript-map";

interface Props {
  placeholder: string;
  onChange: (options: string[]) => void;
}

interface SelectorState {
  macros: string[];
  remoteCommands: RemoteCommand[];
  switches: Switch[];
}

export default class ActionSelector extends React.Component<
  Props,
  SelectorState
> {
  state: SelectorState = {
    macros: [],
    remoteCommands: [],
    switches: []
  };

  public componentDidMount() {
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
  }

  public render() {
    const remotes = new TSMap<
      string,
      TSMap<string, TSMap<string, Set<string>>>
    >();

    const commandSourceName = (source?: RemoteCommandSource) => {
      if (source) {
        return `${source.name}|${source.type}`;
      } else {
        return "local";
      }
    };

    this.state.remoteCommands.forEach((rc: RemoteCommand) => {
      const remote =
        remotes.get(rc.remote) ||
        new TSMap<RemoteCommandSource, TSMap<string, Set<string>>>();
      const source =
        remote.get(commandSourceName(rc.source)) ||
        new TSMap<string, Set<string>>();
      const commands = source.get(rc.device) || new Set<string>();

      commands.add(rc.name);
      source.set(rc.device, commands);
      remote.set(commandSourceName(rc.source), source);
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
        children: remotes.map((sources, remote) => ({
          value: remote,
          label: remote,
          children: sources.map((devices, source) => {
            const sourceInfo = () => {
              if (source === "local") {
                return "local";
              } else if (source) {
                return source.split("|")[0];
              } else {
                return "local";
              }
            };

            return {
              value: source,
              label: sourceInfo(),
              children: devices.map((commands, device) => ({
                value: device,
                label: device,
                children: Array.from(commands.values()).map(command => ({
                  value: command,
                  label: command
                }))
              }))
            };
          })
        }))
      }
    ];

    return (
      <Cascader
        className={"controller-width-class"}
        options={opts}
        onChange={this.props.onChange}
        placeholder={this.props.placeholder}
      />
    );
  }
}
