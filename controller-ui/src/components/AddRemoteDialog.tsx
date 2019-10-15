import * as React from "react";
import ReactModal from "react-modal";
import TextField, { Input } from "@material/react-text-field";
import { RemoteData } from "../types";
import MaterialIcon from "@material/react-material-icon";
import Checkbox from "@material/react-checkbox";
import Select, { Option } from "@material/react-select";
import { TSMap } from "typescript-map";
import Form from "./Form";
import Alert from "./Alert";

export interface Props {
  activities: string[];
  currentRoom: string;
  remotes: TSMap<string, RemoteData>;
  editMode: boolean;
  addRemote: (remote: RemoteData) => void;
  fetchActivities(): void;
}

interface DialogState {
  name?: string;
  activities: TSMap<string, boolean>;
  appearAfter: [string?, number?];
  isOpen: boolean;
  alertOpen: boolean;
  alertMessage?: string;
}

export default class AddRemoteDialog extends React.Component<
  Props,
  DialogState
> {
  private defaultState: DialogState = {
    activities: new TSMap<string, boolean>(),
    appearAfter: [undefined, undefined],
    isOpen: false,
    alertOpen: false,
    alertMessage: undefined
  };

  state = this.defaultState;

  public componentDidMount() {
    ReactModal.setAppElement(document.getElementById("dialog") as HTMLElement);
    this.props.fetchActivities();
  }

  private convertToKebabCase = (string: string) => {
    return string.replace(/\s+/g, "-").toLowerCase();
  };

  private handleSubmit = () => {
    const alert = (message: string) =>
      this.setState({
        alertOpen: true,
        alertMessage: message
      });

    if (this.state.name) {
      const name = this.state.name || "";
      const remoteName = this.convertToKebabCase(name);

      const appearAfter = () => {
        if (this.state.appearAfter[1]) {
          return (this.state.appearAfter[1] || 0) + 1;
        } else {
          return undefined;
        }
      };

      const newRemote: RemoteData = {
        name: remoteName,
        label: name,
        rooms: [this.props.currentRoom],
        activities: this.state.activities.filter((v: boolean) => v).keys(),
        isActive: false,
        buttons: [],
        order: appearAfter(),
        editable: true
      };

      this.setState(this.defaultState);

      fetch(
        `${window.location.protocol}//${window.location.hostname}:8090/config/remote`,
        {
          method: "POST",
          body: JSON.stringify(newRemote)
        }
      ).then(res => {
        if (res.ok) {
          this.props.addRemote(newRemote);
        } else {
          res.text().then(text => alert(`Failed to create remote: ${text}`));
        }
      });
    } else {
      alert("Please enter a remote name");
    }
  };

  private activities = () =>
    this.props.activities.map((activity: string) => {
      return (
        <div key={activity} className="mdc-form-field">
          <Checkbox
            nativeControlId={activity}
            checked={this.state.activities.get(activity) || false}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
              this.setState({
                activities: this.state.activities.set(
                  activity,
                  e.target.checked
                )
              })
            }
          />
          <label
            className="mdc-typography mdc-typography--overline"
            htmlFor={activity}
          >
            {activity}
          </label>
        </div>
      );
    });

  private remotes = () => {
    const opts = this.props.remotes.map(
      (remote: RemoteData, name: string | undefined) => {
        if (name) {
          return (
            <Option key={name} value={name}>
              {remote.label}
            </Option>
          );
        } else {
          return <React.Fragment key={name}></React.Fragment>;
        }
      }
    );

    opts.push(<Option key={""} value={""}></Option>);

    return (
      <Select
        label="Appear After"
        value={this.state.appearAfter[0] || ""}
        onChange={(e: React.ChangeEvent<HTMLSelectElement>) => {
          this.setState({
            appearAfter: [
              e.target.value,
              this.props.remotes.get(e.target.value).order
            ]
          });
        }}
      >
        {opts}
      </Select>
    );
  };

  public render() {
    const elements = new TSMap<string, JSX.Element | JSX.Element[]>()
      .set(
        "Remote Name",
        <TextField
          label="Remote Name"
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
      )
      .set("Appear After", this.remotes())
      .set("Activities", this.activities());

    return (
      <div>
        <Alert
          isOpen={this.state.alertOpen}
          message={this.state.alertMessage}
          onClose={() => this.setState({ alertOpen: false })}
        ></Alert>
        <Form
          name="Add Remote"
          isOpen={this.state.isOpen}
          onSubmit={this.handleSubmit}
          onCancel={() => this.setState({ isOpen: false })}
          elements={elements}
        ></Form>

        <div>
          <div className="center-align" hidden={!this.props.editMode}>
            <button
              id="add-remote-button"
              type="button"
              onClick={() => this.setState({ isOpen: true })}
              className="mdc-fab mdc-button--raised"
            >
              <span className="mdc-fab__icon material-icons">add</span>
            </button>
          </div>
        </div>
      </div>
    );
  }
}
