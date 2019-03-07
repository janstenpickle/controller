import * as React from 'react';
import { RemoteData, RemoteButtons } from '../types/index';
import { Responsive, WidthProvider, Layout, Layouts } from 'react-grid-layout';
import { TSMap } from 'typescript-map';
import { renderButton } from './MainButtons'

const ResponsiveReactGridLayout = WidthProvider(Responsive);

export interface Props {
  remotes: RemoteData[];
  currentRoom: string,
  focus: (remote: string) => void;
  focusedRemote: string;
  fetchRemotes(): void;
  plugState(state: boolean, name?: string): void;
  showAll: boolean;
}

export interface Coords {
  x: number;
  y: number;
}

interface RemotePlacement {
  name: string;
  lg?: Coords;
  md?: Coords;
  sm?: Coords;
  xs?: Coords;
  xxs?: Coords;
}

interface RemoteCoords {
  coords: TSMap<string, RemotePlacement>;
  next: Coords;
}

export default class Remotes extends React.Component<Props,{}> {
  public componentDidMount() {
    this.props.fetchRemotes();
  }

  public render() {
    const filteredRemotes = this.props.remotes.filter((data: RemoteData) => (data.isActive || this.props.showAll) && (data.rooms.length === 0 || data.rooms.includes(this.props.currentRoom)));

    const remotePlacements: RemotePlacement[] = filteredRemotes.map((data: RemoteData) => { return {name: data.name} })

    const divs = filteredRemotes.map((data: RemoteData) => {
      const buttons = data.buttons.map((button: RemoteButtons) => renderButton(button, this.props.currentRoom, this.props.plugState))
      return (
        <div className='mdl-shadow--2dp mdl-color--grey-100' key={data.name}>
          <div className='center-align'>
            {data.name}
          </div>
          <div className='center-align'>
            {buttons}
          </div>
        </div>
      )
    });

    const nextCoords: (cols: number, coords: Coords) => Coords  = (cols: number, coords: Coords) => {
      if (coords.x < (cols - 1)) {
        return { x: coords.x + 1, y: coords.y}
      } else {
        return { x: 0, y: coords.y + 1}
      }
    };

    const cToString = (coords: Coords) => coords.x.toString() + coords.y.toString();

    const placement: (cols: number, rs: RemotePlacement[], selector: (data: RemotePlacement) => Coords|undefined, setter: (coords: Coords, data: RemotePlacement) => RemotePlacement) => RemotePlacement[]  = (cols: number, rs: RemotePlacement[], selector: (data: RemotePlacement) => Coords|undefined, setter: (coords: Coords, data: RemotePlacement) => RemotePlacement) =>
     rs.reduce((a: RemoteCoords, data: RemotePlacement) => {
      const coords = (selector(data) || {x: 0, y: 0})
      if (a.coords.has(cToString(coords)) || coords.x >= (cols - 1)) {
        const next = a.coords.keys().sort().reduce((co: Coords, s: string) => {
          if (s === cToString(co)) {
            return nextCoords(cols, co)
          } else {
            return co
          }
        }, a.next)
        return { coords: a.coords.set(cToString(next), setter(next, data)), next: nextCoords(cols, next) }
      } else {
        return { coords: a.coords.set(cToString(coords), setter(coords, data)), next: a.next }
      }

    }, { coords: new TSMap<string, RemoteData>(), next: {x: 0, y: 0}}).coords.values();

    const layout: (cols: number, selector: (data: RemotePlacement) => Coords|undefined, setter: (coords: Coords, data: RemotePlacement) => RemotePlacement) => Layout[] =
      (cols: number, selector: (data: RemotePlacement) => Coords|undefined, setter: (coords: Coords, data: RemotePlacement) => RemotePlacement) =>
      placement(cols, remotePlacements, selector, setter).map((data: RemotePlacement) => {
        const coords = (selector(data) || {x: 0, y: 0})
        return { i: data.name, x: coords.x, y: coords.y, w: 1, h: 1, isResizable: false, isDraggable: false, }
      });


    const layouts: Layouts = {
      lg: layout(4, (data: RemotePlacement) => data.lg, (coords: Coords, data: RemotePlacement) => { data.lg = coords; return data }),
      md: layout(3, (data: RemotePlacement) => data.md, (coords: Coords, data: RemotePlacement) => { data.md = coords; return data }),
      sm: layout(2, (data: RemotePlacement) => data.sm, (coords: Coords, data: RemotePlacement) => { data.sm = coords; return data }),
      xs: layout(2, (data: RemotePlacement) => data.xs, (coords: Coords, data: RemotePlacement) => { data.xs = coords; return data }),
      xxs: layout(1, (data: RemotePlacement) => data.xxs, (coords: Coords, data: RemotePlacement) => { data.xxs = coords; return data }),
    };

    const cols = {lg: 4, md: 3, sm: 2, xs: 2, xxs: 1};

    const layoutChange: (layout: Layout[], layouts: Layouts) => void = (layout: Layout[], layouts: Layouts) =>
      console.log(layouts);

    return (
      <ResponsiveReactGridLayout className='layout' rowHeight={280} cols={cols} layouts={layouts} containerPadding={[5, 5]} compactType='vertical' onLayoutChange={layoutChange}>
      {divs}
      </ResponsiveReactGridLayout>
    );
  }
}
