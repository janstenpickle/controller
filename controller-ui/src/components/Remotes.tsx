import * as React from 'react';
import { RemoteData, Coords, RemoteButtons } from '../types/index';
import { Responsive, WidthProvider, Layout, Layouts } from 'react-grid-layout';
import { TSMap } from 'typescript-map';
import { renderButton } from './MainButtons'

const ResponsiveReactGridLayout = WidthProvider(Responsive);

export interface Props {
  remotes: RemoteData[];
  focus: (remote: string) => void;
  focusedRemote: string;
  fetchRemotes(): void;
  plugState(state: boolean, name?: string): void;
  showAll: boolean;
}

interface RemoteCoords {
  coords: TSMap<string, RemoteData>,
  next: Coords
}

export default class Remotes extends React.Component<Props,{}> {
  public componentDidMount() {
    this.props.fetchRemotes();
  }

  public render() {
    const filteredRemotes = this.props.remotes.filter((data: RemoteData) => data.isActive || this.props.showAll);

    const divs = filteredRemotes.map((data: RemoteData) => {
      const buttons = data.buttons.map((button: RemoteButtons) => renderButton(button, this.props.plugState))
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

    //console.info(array.reduce((b: String, a: (string | RemoteData)[]) => b + ', ' + String(a.), '', filteredRemotes.entries()))

    const nextCoords: (cols: number, coords: Coords) => Coords  = (cols: number, coords: Coords) => {
      if (coords.x < (cols - 1)) {
        return { x: coords.x + 1, y: coords.y}
      } else {
        return { x: 0, y: coords.y + 1}
      }
    };

    const cToString = (coords: Coords) => coords.x.toString() + coords.y.toString();

    const placement: (cols: number, rs: RemoteData[], selector: (data: RemoteData) => Coords|undefined, setter: (coords: Coords, data: RemoteData) => RemoteData) => RemoteData[]  = (cols: number, rs: RemoteData[], selector: (data: RemoteData) => Coords|undefined, setter: (coords: Coords, data: RemoteData) => RemoteData) =>
     rs.reduce((a: RemoteCoords, data: RemoteData) => {
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

    const layout: (cols: number, selector: (data: RemoteData) => Coords|undefined, setter: (coords: Coords, data: RemoteData) => RemoteData) => Layout[] =
      (cols: number, selector: (data: RemoteData) => Coords|undefined, setter: (coords: Coords, data: RemoteData) => RemoteData) =>
      placement(cols, filteredRemotes, selector, setter).map((data: RemoteData) => {
        const coords = (selector(data) || {x: 0, y: 0})
        return { i: data.name, x: coords.x, y: coords.y, w: 1, h: 1, isResizable: false, isDraggable: false, }
      });


    const layouts: Layouts = {
      lg: layout(4, (data: RemoteData) => data.lg, (coords: Coords, data: RemoteData) => { data.lg = coords; return data }),
      md: layout(3, (data: RemoteData) => data.md, (coords: Coords, data: RemoteData) => { data.md = coords; return data }),
      sm: layout(2, (data: RemoteData) => data.sm, (coords: Coords, data: RemoteData) => { data.sm = coords; return data }),
      xs: layout(2, (data: RemoteData) => data.xs, (coords: Coords, data: RemoteData) => { data.xs = coords; return data }),
      xxs: layout(1, (data: RemoteData) => data.xxs, (coords: Coords, data: RemoteData) => { data.xxs = coords; return data }),
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
