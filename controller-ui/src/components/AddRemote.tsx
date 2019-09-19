import * as React from 'react';
import { RemoteData } from '../types/index';


export interface Props {
    remotes: RemoteData[];
    openModal: () => void
  }

function AddRemote({remotes, openModal}: Props) {

    return(
        <div>
            <div className="mdl-tooltip" data-mdl-for="add-remote-button">
                Add a new remote
            </div>
            <div className='center-align'>
                <button id='add-remote-button' type="button" onClick={openModal} className="mdl-button mdl-js-button mdl-button--fab mdl-button--colored">
                    <i className="material-icons">add</i>
                </button>
            </div>
        </div>
    )
}

export default AddRemote