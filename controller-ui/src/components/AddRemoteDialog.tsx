import * as React from "react";
import ReactModal from "react-modal";
import TextField, { Input } from "@material/react-text-field";
import { RemoteData } from "../types";
import MaterialIcon from "@material/react-material-icon";
import Checkbox from "@material/react-checkbox";
import Select, { Option } from "@material/react-select";
import { TSMap } from "typescript-map";

export interface Props {
  activities: string[];
  currentRoom: string;
  remotes: TSMap<string, RemoteData>;
  editMode: boolean;
  addRemote: (remote: RemoteData) => void;
  fetchActivities(): void;
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
    background: "#f2f2f2"
  }
};

interface DialogState {
  name?: string;
  activities: TSMap<string, boolean>;
  appearAfter: [string?, number?];
  isOpen: boolean;
}

export default class AddRemoteDialog extends React.Component<
  Props,
  DialogState
> {
  private defaultState: DialogState = {
    activities: new TSMap<string, boolean>(),
    appearAfter: [undefined, undefined],
    isOpen: false
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
          alert("Failed to create remote");
        }
      });
    } else {
      alert("Please enter a name");
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
    )

    opts.push(<Option key={''} value={''}></Option>)

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
    return (
      <div>
        <ReactModal
          isOpen={this.state.isOpen}
          shouldCloseOnEsc={true}
          onRequestClose={() => this.setState({ isOpen: false })}
          style={customStyles}
          contentLabel="Add Remote"
        >
          <div className="center-align mdc-typography mdc-typography--overline">
            Add Remote
          </div>

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
          <br />
          {this.remotes()}
          <br />

          {this.activities()}

          <br />

          <button
            onClick={() => this.setState({ isOpen: false })}
            className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--outlined mdc-elevation--z2"
          >
            Cancel
          </button>

          <button
            onClick={this.handleSubmit}
            className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--raised"
          >
            Submit
          </button>
        </ReactModal>

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
