import { Component, inject, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AccountApiService, apiError } from '../core/api.services';
import { BankingStore } from '../core/banking.store';
import { Account, AccountStatus } from '../core/models';
import { IconComponent } from '../shared/icon.component';

@Component({
  selector: 'app-accounts', imports: [DecimalPipe, ReactiveFormsModule, RouterLink, IconComponent],
  template: `
    <div class="page-heading"><div><p class="eyebrow">VOTRE PORTEFEUILLE</p><h1>Accounts</h1><p>Créez et gérez vos comptes bancaires.</p></div><button class="btn btn-primary" (click)="showForm.set(!showForm())"><app-icon [name]="showForm() ? 'close' : 'plus'"/>{{ showForm() ? 'Fermer' : 'Nouveau compte' }}</button></div>
    @if (success()) { <div class="alert alert-success" role="status">{{ success() }}</div> }
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @if (showForm()) { <section class="panel form-panel"><div class="panel-heading"><div><h2>Créer un compte</h2><p>Le solde initial doit être positif ou nul.</p></div></div><form [formGroup]="form" (ngSubmit)="create()" class="form-grid" novalidate>
      <label>Référence client<input formControlName="customerReference" maxlength="255" placeholder="Référence du client"/>@if (invalid('customerReference')) { <span class="field-error">Référence requise, 255 caractères maximum.</span> }</label>
      <label>Devise<input formControlName="currency" maxlength="3" placeholder="EUR" class="uppercase"/>@if (invalid('currency')) { <span class="field-error">Trois lettres majuscules requises.</span> }</label>
      <label>Solde initial<input formControlName="balance" type="number" min="0" step="0.01"/>@if (invalid('balance')) { <span class="field-error">Saisissez un solde positif ou nul.</span> }</label>
      <label>Statut<select formControlName="status"><option value="ACTIVE">ACTIVE</option><option value="BLOCKED">BLOCKED</option></select></label>
      <div class="form-actions"><button class="btn btn-primary" [disabled]="busy()">{{ busy() ? 'Création…' : 'Créer le compte' }}</button></div></form></section> }
    <section class="panel"><div class="panel-heading"><div><h2>Tous les comptes</h2><p>{{ store.accountsLoaded() ? store.accounts().length + ' compte(s)' : 'Données du service Account' }}</p></div><button class="btn btn-secondary" (click)="store.loadAccounts()" [disabled]="store.accountsLoading()"><app-icon name="refresh"/>Actualiser</button></div>
      @if (store.accountsLoading()) { <div class="loading" role="status">Chargement des comptes…</div> }
      @if (store.accountsError()) { <div class="alert alert-error" role="alert">{{ store.accountsError() }}</div> }
      @if (store.accountsLoaded()) { <div class="table-scroll"><table><thead><tr><th>Client / identifiant</th><th>Statut</th><th class="align-right">Solde</th><th class="align-right">Actions</th></tr></thead><tbody>@for (a of store.accounts(); track a.id) { <tr><td><strong>{{ a.customerReference }}</strong><small class="mono">{{ a.id }}</small></td><td><span class="badge" [class.positive]="a.status === 'ACTIVE'" [class.negative]="a.status === 'BLOCKED'">{{ a.status }}</span></td><td class="align-right amount">{{ a.balance | number:'1.2-2' }} <span>{{ a.currency }}</span></td><td class="align-right"><div class="row-actions"><button class="btn btn-small" (click)="details(a.id)" [disabled]="detailBusy()">Détails</button><button class="btn btn-small" (click)="toggle(a)" [disabled]="busy()">{{ a.status === 'ACTIVE' ? 'Bloquer' : 'Débloquer' }}</button></div></td></tr> } @empty { <tr><td colspan="4" class="empty">Aucun compte. Créez un compte pour commencer.</td></tr> }</tbody></table></div> }
    </section>
    @if (selected(); as a) { <section class="panel detail-panel"><div class="panel-heading"><div><h2>Détails du compte</h2><p class="mono">{{ a.id }}</p></div><button class="icon-button" aria-label="Fermer les détails" (click)="selected.set(null)"><app-icon name="close"/></button></div><div class="details-grid"><div><small>Référence client</small><strong>{{ a.customerReference }}</strong></div><div><small>Statut</small><strong>{{ a.status }}</strong></div><div><small>Solde actuel</small><strong>{{ a.balance | number:'1.2-2' }} {{ a.currency }}</strong></div><a routerLink="/transactions" [queryParams]="{accountId: a.id}" class="text-link">Voir l’historique<app-icon name="arrow"/></a></div></section> }`
})
export class AccountsComponent implements OnInit {
  readonly store = inject(BankingStore);
  private readonly api = inject(AccountApiService);
  private readonly fb = inject(FormBuilder);
  readonly showForm = signal(false); readonly busy = signal(false); readonly detailBusy = signal(false);
  readonly error = signal(''); readonly success = signal(''); readonly selected = signal<Account | null>(null);
  readonly form = this.fb.nonNullable.group({ customerReference: ['', [Validators.required, Validators.maxLength(255), Validators.pattern(/\S/)]], currency: ['EUR', [Validators.required, Validators.pattern(/^[A-Z]{3}$/)]], balance: [0, [Validators.required, Validators.min(0)]], status: this.fb.nonNullable.control<AccountStatus>('ACTIVE') });
  ngOnInit() { this.store.loadAccounts(); }
  invalid(name: keyof typeof this.form.controls) { const c = this.form.controls[name]; return c.invalid && c.touched; }
  create() {
    this.form.markAllAsTouched(); if (this.form.invalid || this.busy()) return;
    this.busy.set(true); this.error.set(''); this.success.set('');
    const body = this.form.getRawValue(); body.customerReference = body.customerReference.trim();
    this.api.create(body).pipe(finalize(() => this.busy.set(false))).subscribe({ next: a => { this.success.set('Compte créé avec succès.'); this.selected.set(a); this.showForm.set(false); this.form.reset({currency: 'EUR', balance: 0, status: 'ACTIVE', customerReference: ''}); this.store.loadAccounts(); }, error: e => this.error.set(apiError(e)) });
  }
  details(id: string) {
    this.detailBusy.set(true); this.error.set(''); this.selected.set(null);
    this.api.get(id).pipe(finalize(() => this.detailBusy.set(false))).subscribe({next: a => this.selected.set(a), error: e => this.error.set(apiError(e))});
  }
  toggle(a: Account) {
    this.busy.set(true); this.error.set(''); this.success.set('');
    this.api.status(a.id, a.status === 'ACTIVE' ? 'BLOCKED' : 'ACTIVE').pipe(finalize(() => this.busy.set(false))).subscribe({next: updated => { this.success.set('Statut du compte actualisé.'); if (this.selected()?.id === a.id) this.selected.set(updated); this.store.loadAccounts(); }, error: e => this.error.set(apiError(e))});
  }
}
