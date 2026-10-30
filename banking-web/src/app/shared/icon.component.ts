import { Component, input } from '@angular/core';

@Component({
  selector: 'app-icon',
  host: { 'aria-hidden': 'true' },
  template: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path [attr.d]="paths[name()] || paths['grid']" /></svg>`,
  styles: [`:host{display:inline-flex;width:21px;height:21px;flex-shrink:0}svg{width:100%;height:100%}`]
})
export class IconComponent {
  readonly name = input('grid');
  readonly paths: Record<string, string> = {
    grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
    wallet: 'M3 6V5a2 2 0 0 1 2-2h13v4 M3 6h17v15H5a2 2 0 0 1-2-2V6z M16 12h4v4h-4z',
    arrows: 'M4 7h16l-4-4 M20 17H4l4 4 M20 7l-4 4 M4 17l4-4',
    send: 'm22 2-7 20-4-9-9-4 20-7z M22 2 11 13',
    spark: 'm12 3 2.8 6.2L21 12l-6.2 2.8L12 21l-2.8-6.2L3 12l6.2-2.8L12 3z M20 2v4 M18 4h4',
    plus: 'M12 5v14 M5 12h14',
    arrow: 'M5 12h14 M14 7l5 5-5 5',
    refresh: 'M20 7v5h-5 M4 17v-5h5 M6 6a8 8 0 0 1 14 6 M18 18a8 8 0 0 1-14-6',
    shield: 'm12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3z m-4 9 3 3 5-6',
    menu: 'M4 6h16 M4 12h16 M4 18h16',
    close: 'm6 6 12 12 M18 6 6 18',
    user: 'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0z M4 21v-2a8 8 0 0 1 16 0v2',
    globe: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z M3 12h18 M12 3c-5 5-5 13 0 18 M12 3c5 5 5 13 0 18',
    check: 'm5 12 4 4L19 6'
  };
}
