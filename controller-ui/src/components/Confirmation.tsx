import React from "react";
import Dialog, {
  DialogContent,
  DialogFooter,
  DialogButton
} from "@material/react-dialog";

interface Props {
  isOpen: boolean;
  message?: string;
  onOk: () => void;
  onCancel: () => void;
}

export default class Confirmation extends React.Component<Props, {}> {
  public render() {
    return (
      <Dialog
        onClose={action => {
          if (action === "cancel") {
            this.props.onCancel()
          } else if (action === "ok") {
            this.props.onOk()
          }
        }}
        open={this.props.isOpen}
        escapeKeyAction = "cancel"
        scrimClickAction = "cancel"
      >
        <DialogContent>{this.props.message}</DialogContent>
        <DialogFooter>
          <DialogButton action="cancel" isDefault>
            Cancel
          </DialogButton>
          <DialogButton action="ok">
            OK
          </DialogButton>
        </DialogFooter>
      </Dialog>
    );
  }
}
