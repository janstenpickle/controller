import * as React from "react";
import { RemoteCommand } from "../types";
import Select, { Option } from "@material/react-select";
import ReactModal from "react-modal";
import TextField, { Input } from "@material/react-text-field";
import MaterialIcon from "@material/react-material-icon";
import { remoteCommandsAPI } from "../api/remotecontrol";
import Spinner from "react-spinner-material";
import { TSMap } from "typescript-map";
import Form from "./Form";
import Alert from "./Alert";

const customStyles = {
  content: {
    top: "50%",
    left: "50%",
    right: "auto",
    bottom: "auto",
    marginRight: "-50%",
    transform: "translate(-50%, -50%)",
    padding: "3px",
    background: "#f2f2f2"
  }
};

interface Props {
  isOpen: boolean;
  onRequestClose: () => void;
}

interface DialogState {
  learnFormOpen: boolean;
  remoteCommands: RemoteCommand[];
  remoteName?: string;
  deviceName?: string;
  commandName?: string;
  learnInProgress: boolean;
  learnSuccess: boolean;
  learnResponse?: string;
  alertOpen: boolean;
  alertMessage?: string;
}

export default class LearnRemoteCommandsDialog extends React.Component<
  Props,
  DialogState
> {
  defaultState: DialogState = {
    learnFormOpen: true,
    remoteCommands: [],
    learnInProgress: false,
    learnSuccess: true,
    alertOpen: false
  };

  state: DialogState = this.defaultState;

  public componentDidMount() {
    ReactModal.setAppElement(document.getElementById("dialog") as HTMLElement);
  }

  public render() {
    const alert = (message: string) =>
      this.setState({ alertMessage: message, alertOpen: true });

    const button = () => {
      const handleLearn = () => {
        const remoteName = this.state.remoteName;
        const deviceName = this.state.deviceName;
        const commandName = this.state.commandName;

        const error = () => {
          alert("Please ensure form is filled");
        };

        if (remoteName && deviceName && commandName) {
          if (remoteName !== "" && deviceName !== "" && commandName !== "") {
            this.setState({ learnFormOpen: false, learnInProgress: true });

            fetch(
              `${window.location.protocol}//${window.location.hostname}:8090/control/remote/learn/${remoteName}/${deviceName}/${commandName}`,
              { method: "POST" }
            ).then(res => {
              res.text().then(body =>
                this.setState({
                  learnFormOpen: false,
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

        const elements = () =>
          new TSMap<string, JSX.Element>()
            .set(
              "Remote Control",
              <Select
                label="Remote Control"
                outlined={true}
                value={this.state.remoteName || ""}
                selectClassName={"controller-width-class"}
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
            )
            .set(
              "Device Name",
              <TextField
                label="Device Name"
                outlined={true}
                dense={true}
                className={"controller-width-class"}
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
            )
            .set(
              "Command Name",
              <TextField
                label="Command Name"
                outlined={true}
                dense={true}
                className={"controller-width-class"}
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
            );

        return (
          <Form
            name={"Learn Remote Command"}
            isOpen={
              this.props.isOpen &&
              this.state.learnFormOpen &&
              !this.state.learnInProgress
            }
            onCancel={() => {
              this.setState(this.defaultState);
              this.props.onRequestClose();
            }}
            onSubmit={handleLearn}
            elements={elements()}
            onAfterOpen={() =>
              remoteCommandsAPI
                .fetchRemoteCommandsAsync()
                .then((remoteCommands: RemoteCommand[]) =>
                  this.setState({
                    remoteCommands
                  })
                )
            }
          ></Form>
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
              onClick={() => {
                this.setState(this.defaultState);
                this.props.onRequestClose();
              }}
              className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--outlined mdc-elevation--z2"
            >
              Cancel
            </button>
            <button
              onClick={() =>
                this.setState({
                  ...this.defaultState,
                  remoteCommands: this.state.remoteCommands
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
        } else if (this.state.learnInProgress) {
          return dialogLoading();
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
          {learnForm()}
          <ReactModal
            isOpen={this.props.isOpen && !this.state.learnFormOpen}
            shouldCloseOnEsc={true}
            onRequestClose={() => {
              this.setState(this.defaultState);
              this.props.onRequestClose();
            }}
            style={customStyles}
          >
            {dialogContent()}
          </ReactModal>
        </React.Fragment>
      );
    };

    return button();
  }
}
