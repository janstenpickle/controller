import React from "react";
import Dialog, {
  DialogContent,
  DialogFooter,
  DialogButton
} from "@material/react-dialog";

interface Props {
  isOpen: boolean;
  message?: string;
  onClose: () => void;
}

export default class Alert extends React.Component<Props, {}> {
  public render() {
    return (
      <Dialog onClose={_ => this.props.onClose()} open={this.props.isOpen}>
        <DialogContent>{this.props.message}</DialogContent>
        <DialogFooter>
          <DialogButton action="ok" isDefault>
            OK
          </DialogButton>
        </DialogFooter>
      </Dialog>
    );
  }
}
