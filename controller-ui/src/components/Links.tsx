import * as React from 'react';
import * as Scroll from 'react-scroll';
import { RemoteData } from '../types/index';

const Link = Scroll.Link;

export interface Props {
  remotes: RemoteData[];
  focus: (remote: string) => void;
  toggle: (remote: string, value: boolean) => void;
}

function Links({ remotes, focus, toggle }: Props) {
  const remoteLinks = remotes.map((remoteData: RemoteData) => {
    const doFocus = () => {
      focus(remoteData.name);
    };

    const doToggle = () => {
      const newActive = !remoteData.isActive;
      toggle(remoteData.name, newActive);
      if (newActive) { focus(remoteData.name); }
    };

    const remoteLink = () => { if (remoteData.isActive) {
        return <Link className='mdl-navigation__link' onClick={doFocus} to={remoteData.name}>{remoteData.name}</Link>;
      } else {
        return <a className='mdl-navigation__link' onClick={doToggle} href='#'>{remoteData.name}</a>;
      }
    };

    console.error(remoteLink())

    return (
      <li key={remoteData.name} className='mdl-list__item'>
        <span className='mdl-list__item-primary-content'>
          <span>{remoteLink()}</span>
        </span>
        <span className='mdl-list__item-secondary-action'>
          <label className='mdl-switch mdl-js-switch mdl-js-ripple-effect'>
            <input
              type='checkbox'
              id={remoteData.name}
              className='mdl-switch__input'
              checked={remoteData.isActive}
              onChange={doToggle}
            />
          </label>
          <span className='mdl-switch__label'></span>
        </span>
      </li>
    );

  });

  return (
    <div>
      <ul className='mdl-list'>
      {remoteLinks}
      </ul>
    </div>
  );
}

export default Links;
