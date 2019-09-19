import * as React from 'react';
import ReactModal from 'react-modal';


export interface Props {
    activities: string[],
    isOpen: boolean;
    openModal: () => void;
    closeModal: () => void;
    addRemote: (remote: string, activities: string[]) => void;
  }

const customStyles = {
    content : {
        top                   : '50%',
        left                  : '50%',
        right                 : 'auto',
        bottom                : 'auto',
        marginRight           : '-50%',
        transform             : 'translate(-50%, -50%)',
        padding               : '5px',
    }
};

function AddRemoteDialog(props: Props) {
    ReactModal.setAppElement(document.getElementById('dialog')as HTMLElement)

    function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault()
        props.closeModal()
        const target = event.target as HTMLFormElement
        const form = new FormData(target)

        const activities = props.activities.filter((activity: string) => 
            form.get(activity)
        )

        props.addRemote(form.get("remote-name") as string, activities)
      }

    const derp =props.activities.map((activity: string) => {
        return(<div key={activity}>
            <label key={activity}>
                {activity} <input key={activity} name={activity} type="checkbox" defaultChecked={false} />
            </label><br/>
        </div>
        )     
    })
     

    return(
        <div>
            <ReactModal
                isOpen={props.isOpen}
                shouldCloseOnEsc={true}
                onRequestClose={props.closeModal}
                style={customStyles}
                bodyOpenClassName="mdl-color--grey-100"
                contentLabel="Add Remote"
            >
            <div className="mdl-shadow--2dp mdl-color--grey-100">
                
                <div>Enter Remote Name</div>
                <form onSubmit={handleSubmit}>
                <div className="mdl-textfield mdl-js-textfield mdl-textfield--floating-label">
                    <input className="mdl-textfield__input" type="text" id="remote-name-input" name="remote-name" pattern="([^\s]*)" />
                    <span className="mdl-textfield__error">Input is not a number!</span>
                </div>
                <br/>
                {derp}
                <div>
                <button onClick={props.closeModal} className="mdl-button mdl-js-button mdl-js-ripple-effect mdl-button--raised mdl-button">Cancel</button>
                    <input type="submit" className="mdl-button mdl-js-button mdl-js-ripple-effect mdl-button--raised mdl-button--colored" value="Submit" />
                </div>
                </form>
            
            </div>
            </ReactModal>
        </div>
    )
}

export default AddRemoteDialog