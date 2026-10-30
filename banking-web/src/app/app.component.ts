import { Component, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { IconComponent } from './shared/icon.component';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, IconComponent],
  template: `
    <a class="skip-link" href="#main-content">Aller au contenu</a>
    @if (menuOpen()) { <button class="sidebar-backdrop" aria-label="Fermer la navigation" (click)="menuOpen.set(false)"></button> }
    <aside class="sidebar" [class.is-open]="menuOpen()">
      <a routerLink="/dashboard" class="brand"><span class="brand-symbol">A<span></span></span><span>ADRIA<small>BANKING PLATFORM</small></span></a>
      <div class="workspace"><span class="workspace-icon">A</span><span>Espace bancaire<small>Gestion des opérations</small></span><span class="workspace-dot"></span></div>
      <p class="nav-label">WORKSPACE</p>
      <nav aria-label="Navigation principale">@for (item of navigation; track item.path) {
        <a [routerLink]="item.path" routerLinkActive="active" (click)="menuOpen.set(false)"><app-icon [name]="item.icon"/>{{ item.label }}@if (item.icon === 'spark') { <span class="nav-tag">AI</span> }</a>
      }</nav>
      <div class="sidebar-bottom"><div class="security-note"><app-icon name="shield"/><strong>Vos opérations, centralisées.</strong><p>Un espace pour piloter vos comptes et votre activité.</p></div><div class="sidebar-footer">ADRIA Banking Platform <span>v1.0</span></div></div>
    </aside>
    <div class="main-shell"><header class="topbar"><div class="flex items-center gap-3"><button class="icon-button mobile-menu" aria-label="Ouvrir la navigation" [attr.aria-expanded]="menuOpen()" (click)="menuOpen.set(!menuOpen())"><app-icon name="menu"/></button><span class="topbar-label">Banking workspace</span><span class="topbar-divider">/</span><span class="topbar-subtitle">Vue d’ensemble</span></div><div class="topbar-right"><span class="local-badge"><span></span>Environnement local</span><span class="avatar">AD</span></div></header>
      <main id="main-content" tabindex="-1"><router-outlet/></main>
      <footer class="main-footer"><span>© {{ year }} ADRIA Banking Platform</span><span>Comptes · Opérations · Intelligence</span></footer>
    </div>`
})
export class AppComponent {
  readonly menuOpen = signal(false);
  readonly year = new Date().getFullYear();
  readonly navigation = [
    { path: '/dashboard', label: 'Dashboard', icon: 'grid' },
    { path: '/accounts', label: 'Accounts', icon: 'wallet' },
    { path: '/transactions', label: 'Transactions', icon: 'arrows' },
    { path: '/transfers', label: 'Transfers', icon: 'send' },
    { path: '/assistant', label: 'AI Assistant', icon: 'spark' }
  ];
}
