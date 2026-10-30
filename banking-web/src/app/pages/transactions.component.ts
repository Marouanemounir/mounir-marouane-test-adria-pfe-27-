import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, of, switchMap, tap } from 'rxjs';
import { TransactionApiService, apiError, isDefinitiveRejection } from '../core/api.services';
import { BankingStore } from '../core/banking.store';
import { PendingOperationService } from '../core/pending-operation.service';
import { CreateTransactionRequest, Transaction } from '../core/models';
import { IconComponent } from '../shared/icon.component';
import { TransactionTableComponent } from '../shared/transaction-table.component';

@Component({
  selector: 'app-transactions', imports: [ReactiveFormsModule, IconComponent, TransactionTableComponent],
  template: `
    <div class="page-heading"><div><p class="eyebrow">SUIVI DE L’ACTIVITÉ</p><h1>Transactions</h1><p>Consultez l’historique et enregistrez un débit.</p></div><button class="btn btn-primary" (click)="showForm.set(!showForm())"><app-icon name="plus"/>Nouvelle transaction</button></div>
    @if (success()) { <div class="alert alert-success" role="status">{{ success() }}</div> }
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @if (store.accountsError()) { <div class="alert alert-error" role="alert">Comptes : {{ store.accountsError() }} <button class="text-link" (click)="store.loadAccounts()">Réessayer</button></div> }
    @if (showForm()) { <section class="panel form-panel"><div class="panel-heading"><div><h2>Nouvelle transaction</h2><p>Montant positif, plafond indicatif : 1 000. Le backend valide l’opération.</p></div></div>
    @if (pendingKey()) { <div class="alert alert-info">Opération : {{ pendingKey() }}. {{ form.disabled ? 'Résultat non confirmé : réessayez avec la même clé et le même contenu.' : 'Rejet confirmé. Vous pouvez corriger les champs ; un retry identique conserve la même clé.' }}</div> }
    <form [formGroup]="form" (ngSubmit)="submit()" class="form-grid" novalidate><label>Compte<select formControlName="accountId" (change)="accountChanged()"><option value="">Sélectionner un compte</option>@for (a of store.accounts(); track a.id) { <option [value]="a.id">{{ a.customerReference }} · {{ a.currency }} · {{ a.id.slice(0,8) }}</option> }</select></label>
    <label>Montant<input formControlName="amount" type="number" min="0.01" max="1000" step="0.01"/></label><label>Devise<input formControlName="currency" maxlength="3" placeholder="EUR"/></label><label>Catégorie commerçant<input formControlName="merchantCategory" maxlength="100" placeholder="Ex. GROCERIES"/></label><label>Pays<input formControlName="country" maxlength="100" placeholder="Ex. MA"/></label>
    @if (form.invalid && attempted()) { <p class="field-error full-width">Tous les champs sont requis. Montant de 0,01 à 1 000 avec deux décimales maximum ; devise de trois lettres majuscules.</p> }
    <div class="form-actions"><button class="btn btn-primary" [disabled]="busy() || store.accountsLoading()">{{ busy() ? 'Envoi…' : pendingKey() ? 'Réessayer la même opération' : 'Confirmer la transaction' }}</button></div></form></section> }
    <section class="panel"><div class="panel-heading"><div><h2>Historique des transactions</h2><p>Les opérations retournées par Transaction Service</p></div><div class="flex gap-3 flex-wrap"><select class="filter-select" aria-label="Filtrer par compte" [formControl]="filter"><option value="">Tous les comptes</option>@for (a of store.accounts(); track a.id) { <option [value]="a.id">{{ a.customerReference }} · {{ a.id.slice(0,8) }}</option> }</select><button class="btn btn-secondary" (click)="refresh()" [disabled]="loading()"><app-icon name="refresh"/>Actualiser</button></div></div>
    @if (loading()) { <div class="loading" role="status">Chargement des transactions…</div> }
    @if (listError()) { <div class="alert alert-error" role="alert">{{ listError() }}</div> }
    @if (!loading() && !listError()) { <app-transaction-table [transactions]="items()"/> }</section>`
})
export class TransactionsComponent implements OnInit {
  readonly store = inject(BankingStore); private readonly api = inject(TransactionApiService); private readonly pending = inject(PendingOperationService);
  private readonly fb = inject(FormBuilder); private readonly route = inject(ActivatedRoute); private readonly destroyRef = inject(DestroyRef);
  readonly showForm = signal(false); readonly busy = signal(false); readonly loading = signal(false); readonly attempted = signal(false);
  readonly error = signal(''); readonly listError = signal(''); readonly success = signal(''); readonly items = signal<Transaction[]>([]); readonly pendingKey = signal('');
  readonly filter = this.fb.nonNullable.control('');
  readonly form = this.fb.nonNullable.group({accountId: ['', Validators.required], amount: [0, [Validators.required, Validators.min(0.01), Validators.max(1000), Validators.pattern(/^\d+(\.\d{1,2})?$/)]], currency: ['EUR', [Validators.required, Validators.pattern(/^[A-Z]{3}$/)]], merchantCategory: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]], country: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]]});
  ngOnInit() {
    this.store.loadAccounts();
    const saved = this.pending.read<CreateTransactionRequest>('transaction');
    if (saved) { this.form.patchValue(saved.body); if (!saved.editable) this.form.disable(); this.pendingKey.set(saved.key); this.showForm.set(true); }
    this.filter.setValue(this.route.snapshot.queryParamMap.get('accountId') || '', {emitEvent: false});
    this.filter.valueChanges.pipe(takeUntilDestroyed(this.destroyRef), tap(() => this.load())).subscribe(); this.load();
  }
  // switchMap cancels an older history request if a filter changes rapidly.
  private loadSubscription?: { unsubscribe(): void };
  load() {
    this.loadSubscription?.unsubscribe(); this.loading.set(true); this.listError.set('');
    this.loadSubscription = of(this.filter.value).pipe(switchMap(id => id ? this.api.history(id) : this.api.list()), finalize(() => this.loading.set(false)), takeUntilDestroyed(this.destroyRef)).subscribe({next: data => this.items.set([...data].sort((a,b) => Date.parse(b.createdAt)-Date.parse(a.createdAt))), error: e => this.listError.set(apiError(e))});
  }
  refresh() { this.load(); this.store.refresh(); }
  accountChanged() { const a = this.store.accounts().find(a => a.id === this.form.controls.accountId.value); if (a) this.form.controls.currency.setValue(a.currency); }
  submit() {
    this.attempted.set(true); this.form.markAllAsTouched(); if (this.busy() || (this.form.enabled && this.form.invalid)) return;
    const op = this.pending.begin('transaction', this.form.getRawValue()); this.pendingKey.set(op.key); this.form.disable(); this.busy.set(true); this.error.set(''); this.success.set('');
    this.api.create(op.body, op.key).pipe(finalize(() => this.busy.set(false)), takeUntilDestroyed(this.destroyRef)).subscribe({next: receipt => { this.pending.complete('transaction'); this.pendingKey.set(''); this.form.enable(); this.form.reset({accountId: '', amount: 0, currency: 'EUR', merchantCategory: '', country: ''}); this.attempted.set(false); this.success.set('Transaction confirmée : ' + receipt.id); this.showForm.set(false); this.refresh(); }, error: e => { this.error.set(apiError(e)); if (isDefinitiveRejection(e)) { this.pending.rejected('transaction'); this.form.enable(); } this.store.refresh(); }});
  }
}
