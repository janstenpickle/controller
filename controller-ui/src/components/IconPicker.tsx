import * as React from "react";
import ReactModal from "react-modal";
import { names } from "../common/Icons";
import { Cell, Grid, Row } from "@material/react-layout-grid";
import IconButton from "@material/react-icon-button";
import MaterialIcon from "@material/react-material-icon";

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
  },
  overlay: {
    zIndex: 9999
  }
};

interface Props {
  currentValue: string;
  onSubmit: (icon: string) => void;
  itemsPerPage?: number;
}

interface IconPickerState {
  isOpen: boolean;
  filterText?: string;
  index: number;
}

export default class IconPicker extends React.Component<
  Props,
  IconPickerState
> {
  state: IconPickerState = {
    isOpen: false,
    index: 0
  };

  public componentDidMount() {
    ReactModal.setAppElement(document.getElementById("dialog") as HTMLElement);
  }

  public render() {
    const defaultIcons = () => {
      if (document.documentElement.clientWidth < 900) {
        return 40;
      } else {
        return 150;
      }
    };

    const itemsPerPage = this.props.itemsPerPage || defaultIcons();

    const icons = names
      .slice(this.state.index, this.state.index + itemsPerPage)
      .map(icon => (
        <IconButton
          key={icon}
          onClick={() => {
            this.setState({ isOpen: false });
            this.props.onSubmit(icon);
          }}
        >
          <MaterialIcon icon={icon} />
        </IconButton>
      ));

    const chunkArrayInGroups = (arr: JSX.Element[], size: number) => {
      var myArray = [];
      for (var i = 0; i < arr.length; i += size) {
        myArray.push(arr.slice(i, i + size));
      }
      return myArray;
    };

    const iconRows = chunkArrayInGroups(icons, 20).map((icons, idx) => {
      return (
        <Row key={idx}>
          <Cell desktopColumns={12} phoneColumns={4} tabletColumns={8}>
            {icons}
          </Cell>
        </Row>
      );
    });

    const backButton = () => {
      if (this.state.index > 0) {
        return (
          <button
            className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--raised"
            onClick={() =>
              this.setState({ index: this.state.index - itemsPerPage })
            }
          >
            back
          </button>
        );
      } else {
        return <React.Fragment />;
      }
    };

    const nextButton = () => {
      if (this.state.index + itemsPerPage < names.length) {
        return (
          <button
            className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--raised"
            onClick={() =>
              this.setState({ index: this.state.index + itemsPerPage })
            }
          >
            next
          </button>
        );
      } else {
        return <React.Fragment />;
      }
    };

    return (
      <React.Fragment>
        <i
          className="icon material-icons"
          onClick={() => this.setState({ isOpen: true })}
        >
          {this.props.currentValue}
        </i>

        <ReactModal
          isOpen={this.state.isOpen}
          shouldCloseOnEsc={true}
          onRequestClose={() => {
            this.setState({ isOpen: false });
          }}
          style={customStyles}
        >
          <Grid>
            {iconRows}
            <br />
            <Row>
              <Cell desktopColumns={11} phoneColumns={3} tabletColumns={7}>
                {backButton()}
              </Cell>
              <Cell desktopColumns={1} phoneColumns={1} tabletColumns={1}>
                {nextButton()}
              </Cell>
            </Row>
          </Grid>
        </ReactModal>
      </React.Fragment>
    );
  }
}
