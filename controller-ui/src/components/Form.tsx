import * as React from "react";
import ReactModal from "react-modal";
import { TSMap } from "typescript-map";
import { Cell, Grid, Row } from "@material/react-layout-grid";
import { StyleSheet, css } from "aphrodite";

interface Props {
  name: string;
  isOpen: boolean;
  onSubmit: () => void;
  onCancel: () => void;
  elements: TSMap<string, JSX.Element | JSX.Element[]>;
  customStyles?: ReactModal.Styles;
  submitButtonName?: string;
  cancelButtonName?: string;
  onAfterOpen?: () => void;
  onDelete?: () => void;
}

export default class Form extends React.Component<Props, {}> {
  public componentDidMount() {
    ReactModal.setAppElement(document.getElementById("dialog") as HTMLElement);
  }

  public render() {
    const customStyles = this.props.customStyles || {
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
        zIndex: 1
      }
    };

    const styles = StyleSheet.create({
      red: {
        backgroundColor: "red"
      }
    });

    const submitButtonName = this.props.submitButtonName || "Submit";
    const cancelButtonName = this.props.cancelButtonName || "Cancel";

    const content = this.props.elements.map((element, name) => (
      <React.Fragment key={name}>
        <Row>
          <Cell
            desktopColumns={4}
            tabletColumns={2}
            phoneColumns={1}
            align="middle"
          >
            <div
              className={"mdc-typography mdc-typography--overline"}
            >
              {name}
            </div>
          </Cell>
          <Cell
            desktopColumns={8}
            tabletColumns={6}
            phoneColumns={3}
            align="middle"
          >
            {element}
          </Cell>
        </Row>
        <Row>
          <Cell desktopColumns={12} tabletColumns={6} phoneColumns={4}>
            <hr />
          </Cell>
        </Row>
      </React.Fragment>
    ));

    const deleteButton = () => {
      if (this.props.onDelete) {
        return (
          <button
            onClick={this.props.onDelete}
            className={`mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--raised ${css(
              styles.red
            )}`}
          >
            Delete
          </button>
        );
      } else {
        return <React.Fragment></React.Fragment>;
      }
    };

    return (
      <ReactModal
        isOpen={this.props.isOpen}
        shouldCloseOnEsc={true}
        onRequestClose={this.props.onCancel}
        onAfterOpen={this.props.onAfterOpen}
        style={customStyles}
      >
        <div className="center-align mdc-typography mdc-typography--overline">
          {this.props.name}
        </div>

        <Grid>
          {content}

          <Row>
            <Cell desktopColumns={10} tabletColumns={4} phoneColumns={2}>
              {deleteButton()}
            </Cell>
            <Cell desktopColumns={1} tabletColumns={1} phoneColumns={1}>
              <button
                onClick={this.props.onCancel}
                className="mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--outlined mdc-elevation--z2"
              >
                {cancelButtonName}
              </button>
            </Cell>
            <Cell desktopColumns={1} tabletColumns={1} phoneColumns={1}>
              <div className="right-align">
                <button
                  className=" mdc-ripple-upgraded mdc-button mdc-button--dense mdc-button--raised"
                  onClick={this.props.onSubmit}
                >
                  {submitButtonName}
                </button>
              </div>
            </Cell>
          </Row>
        </Grid>
      </ReactModal>
    );
  }
}
