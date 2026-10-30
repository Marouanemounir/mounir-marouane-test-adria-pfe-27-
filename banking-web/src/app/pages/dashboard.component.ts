import { Component, computed, inject, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { BankingStore } from '../core/banking.store';
import { IconComponent } from '../shared/icon.component';
import { TransactionTableComponent } from '../shared/transaction-table.component';

@Component({
  selector: 'app-dashboard',
  imports: [DecimalPipe, RouterLink, IconComponent, TransactionTableComponent],
  template: `
    <div class="page-heading"><div><p class="eyebrow">VOTRE ESPACE FINANCIER</p><h1>Dashboard</h1><p>Une vision claire de votre activité bancaire.</p></div><button class="btn btn-secondary" (click)="store.refresh()" [disabled]="store.accountsLoading() || store.transactionsLoading()"><app-icon name="refresh"/>Actualiser</button></div>
    <section class="welcome-panel"><div><span class="welcome-tag">ADRIA BANKING PLATFORM</span><h2>Gardez une longueur d’avance<br>sur vos finances.</h2><p>Consultez vos comptes, suivez vos opérations<br>et prenez vos décisions en toute clarté.</p><a routerLink="/transfers" class="btn btn-white">Effectuer un virement<app-icon name="arrow"/></a></div><div class="welcome-art" aria-hidden="true"><div class="orbit orbit-one"></div><div class="orbit orbit-two"></div><div class="bank-card"><div class="flex justify-between"><strong>ADRIA</strong><app-icon name="wallet"/></div><div class="card-chip"></div><span class="card-digits">•••• &nbsp; •••• &nbsp; ••••</span><small>BANKING, SIMPLIFIED.</small></div></div></section>
    <div class="stats-grid"><section class="stat-card"><div class="stat-top"><span>Total des comptes</span><app-icon name="wallet"/></div><strong>{{ store.accountsLoaded() ? store.accounts().length : '—' }}</strong><small>Comptes enregistrés</small></section>
    <section class="stat-card balance-stat"><div class="stat-top"><span>Soldes par devise</span><app-icon name="globe"/></div>@if (store.accountsLoaded()) { @for (balance of balances(); track balance.currency) { <div class="balance-line"><strong>{{ balance.total | number:'1.2-2' }}</strong><span>{{ balance.currency }}</span></div> } @empty { <strong>—</strong><small>Aucun compte</small> } } @else { <strong>—</strong><small>Données indisponibles</small> }</section>
    <section class="stat-card"><div class="stat-top"><span>Transactions</span><app-icon name="arrows"/></div><strong>{{ store.transactionsLoaded() ? store.transactions().length : '—' }}</strong><small>Opérations enregistrées</small></section></div>
    <div class="dashboard-columns"><section class="panel"><div class="panel-heading"><div><h2>Vos comptes</h2><p>Les soldes de votre portefeuille</p></div><a routerLink="/accounts" class="text-link">Tout voir <app-icon name="arrow"/></a></div>
      @if (store.accountsLoading()) { <div class="loading" role="status">Chargement des comptes…</div> }
      @if (store.accountsError()) { <div class="alert alert-error" role="alert">{{ store.accountsError() }}</div> }
      @if (store.accountsLoaded()) { <div class="account-previews">@for (account of store.accounts().slice(0, 4); track account.id) { <a routerLink="/accounts" class="account-preview"><span class="account-icon"><app-icon name="wallet"/></span><div><strong>{{ account.customerReference }}</strong><small><span class="status-dot" [class.blocked]="account.status === 'BLOCKED'"></span>{{ account.status }} · {{ account.currency }}</small></div><b>{{ account.balance | number:'1.2-2' }}<small>{{ account.currency }}</small></b></a> } @empty { <div class="empty">Aucun compte. <a routerLink="/accounts">Créer votre premier compte</a></div> }</div> }
    </section><section class="assistant-promo"><span class="promo-icon"><app-icon name="spark"/></span><span class="eyebrow">VOTRE COPILOTE BANCAIRE</span><h2>Des réponses.<br>Plus simplement.</h2><p>Interrogez votre assistant IA sur vos comptes et votre activité.</p><a routerLink="/assistant" class="btn btn-secondary">Ouvrir l’assistant<app-icon name="arrow"/></a><small>Connecté à votre service Banking Agent</small></section></div>
    <section class="panel"><div class="panel-heading"><div><h2>Transactions récentes</h2><p>Les dernières opérations de vos comptes</p></div><a routerLink="/transactions" class="text-link">Tout voir<app-icon name="arrow"/></a></div>
    @if (store.transactionsLoading()) { <div class="loading" role="status">Chargement des transactions…</div> }
    @if (store.transactionsError()) { <div class="alert alert-error" role="alert">{{ store.transactionsError() }}</div> }
    @if (store.transactionsLoaded()) { <app-transaction-table [transactions]="recent()"/> }</section>`
})
export class DashboardComponent implements OnInit {
  readonly store = inject(BankingStore);
  readonly balances = computed(() => {
    const sums = new Map<string, number>();
    for (const a of this.store.accounts()) sums.set(a.currency, (sums.get(a.currency) || 0) + a.balance);
    return Array.from(sums, ([currency, total]) => ({ currency, total }));
  });
  readonly recent = computed(() => [...this.store.transactions()].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)).slice(0, 5));
  ngOnInit() { this.store.refresh(); }
}
