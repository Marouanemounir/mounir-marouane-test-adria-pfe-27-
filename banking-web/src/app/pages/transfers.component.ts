import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { TransactionApiService, apiError, isDefinitiveRejection } from '../core/api.services';
import { BankingStore } from '../core/banking.store';
import { PendingOperationService } from '../core/pending-operation.service';
import { TransferRequest, TransferResponse } from '../core/models';
import { IconComponent } from '../shared/icon.component';

@Component({
  selector: 'app-transfers', imports: [ReactiveFormsModule, DecimalPipe, IconComponent],
  template: `
    <div class="page-heading"><div><p class="eyebrow">ENTRE VOS COMPTES</p><h1>Transfers</h1><p>Transférez des fonds en quelques étapes.</p></div><button class="btn btn-secondary" (click)="store.loadAccounts()" [disabled]="store.accountsLoading()"><app-icon name="refresh"/>Actualiser les comptes</button></div>
    @if (store.accountsLoading()) { <div class="loading" role="status">Chargement des comptes…</div> }
    @if (store.accountsError()) { <div class="alert alert-error" role="alert">{{ store.accountsError() }}</div> }
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @if (receipt(); as r) { <div class="alert alert-success" role="status"><strong>Virement confirmé par l’API.</strong> {{ r.amount | number:'1.2-2' }} {{ r.currency }} · {{ r.transferId }}<br>Solde source : {{ r.sourceBalance | number:'1.2-2' }} {{ r.currency }} · Solde destination : {{ r.destinationBalance | number:'1.2-2' }} {{ r.currency }}</div> }
    <div class="transfer-layout"><section class="panel form-panel"><div class="panel-heading"><div><h2>Nouveau virement</h2><p>Les deux comptes doivent utiliser la même devise.</p></div><span class="section-icon"><app-icon name="send"/></span></div>
    @if (pendingKey()) { <div class="alert alert-info">Opération : {{ pendingKey() }}. {{ form.disabled ? 'Résultat non confirmé : les retries conservent la clé et le contenu.' : 'Rejet confirmé : corrigez les champs ou réessayez avec la même clé.' }}</div> }
    <form [formGroup]="form" (ngSubmit)="submit()" class="form-grid" novalidate><label class="full-width">Compte source<select formControlName="sourceAccountId" (change)="sourceChanged()"><option value="">Sélectionner le compte à débiter</option>@for (a of store.accounts(); track a.id) { <option [value]="a.id" [disabled]="a.id === form.controls.destinationAccountId.value">{{ a.customerReference }} · {{ a.balance | number:'1.2-2' }} {{ a.currency }} · {{ a.id.slice(0,8) }}</option> }</select></label><div class="transfer-divider full-width"><span><app-icon name="arrows"/></span></div>
    <label class="full-width">Compte destination<select formControlName="destinationAccountId"><option value="">Sélectionner le compte à créditer</option>@for (a of store.accounts(); track a.id) { <option [value]="a.id" [disabled]="a.id === form.controls.sourceAccountId.value">{{ a.customerReference }} · {{ a.currency }} · {{ a.id.slice(0,8) }}</option> }</select></label><label>Montant<input formControlName="amount" type="number" min="0.01" step="0.01" max="1000"/></label><label>Devise<input formControlName="currency" maxlength="3" placeholder="EUR"/></label>
    @if (form.invalid && attempted()) { <p class="field-error full-width">Sélectionnez deux comptes différents. Montant de 0,01 à 1 000, deux décimales maximum, devise de trois lettres majuscules.</p> }
    <div class="form-actions"><button class="btn btn-primary" [disabled]="busy() || store.accountsLoading()"><app-icon name="send"/>{{ busy() ? 'Confirmation en cours…' : pendingKey() ? 'Réessayer le même virement' : 'Confirmer le virement' }}</button></div></form></section><aside class="transfer-help"><span class="promo-icon"><app-icon name="shield"/></span><h2>Chaque étape compte.</h2><p>Vérifiez les comptes et le montant avant de confirmer votre virement.</p><div><span>01</span><p>Sélectionnez le compte source et le compte destination.</p></div><div><span>02</span><p>Indiquez le montant et la devise.</p></div><div><span>03</span><p>La confirmation s’affiche après validation du service bancaire.</p></div><small>Plafond indicatif : 1 000 par opération. Les règles du backend s’appliquent.</small></aside></div>`
})
export class TransfersComponent implements OnInit {
  readonly store = inject(BankingStore); private readonly api = inject(TransactionApiService); private readonly pending = inject(PendingOperationService); private readonly fb = inject(FormBuilder); private readonly destroyRef = inject(DestroyRef);
  readonly busy = signal(false); readonly attempted = signal(false); readonly error = signal(''); readonly pendingKey = signal(''); readonly receipt = signal<TransferResponse | null>(null);
  readonly form = this.fb.nonNullable.group({sourceAccountId: ['', Validators.required], destinationAccountId: ['', Validators.required], amount: [0, [Validators.required, Validators.min(0.01), Validators.max(1000), Validators.pattern(/^\d+(\.\d{1,2})?$/)]], currency: ['EUR', [Validators.required, Validators.pattern(/^[A-Z]{3}$/)]]}, {validators: (c: AbstractControl) => c.get('sourceAccountId')?.value && c.get('sourceAccountId')?.value === c.get('destinationAccountId')?.value ? {sameAccount: true} : null});
  ngOnInit() { this.store.loadAccounts(); const saved = this.pending.read<TransferRequest>('transfer'); if (saved) { this.form.patchValue(saved.body); if (!saved.editable) this.form.disable(); this.pendingKey.set(saved.key); } }
  sourceChanged() { const a = this.store.accounts().find(a => a.id === this.form.controls.sourceAccountId.value); if (a) this.form.controls.currency.setValue(a.currency); }
  submit() {
    this.attempted.set(true); this.form.markAllAsTouched(); if (this.busy() || (this.form.enabled && this.form.invalid)) return;
    const op = this.pending.begin('transfer', this.form.getRawValue()); this.form.disable(); this.pendingKey.set(op.key); this.busy.set(true); this.error.set(''); this.receipt.set(null);
    this.api.transfer(op.body, op.key).pipe(finalize(() => this.busy.set(false)), takeUntilDestroyed(this.destroyRef)).subscribe({next: receipt => { this.receipt.set(receipt); this.pending.complete('transfer'); this.pendingKey.set(''); this.form.enable(); this.form.reset({sourceAccountId: '', destinationAccountId: '', amount: 0, currency: 'EUR'}); this.attempted.set(false); this.store.refresh(); }, error: e => { this.error.set(apiError(e)); if (isDefinitiveRejection(e)) { this.pending.rejected('transfer'); this.form.enable(); } this.store.refresh(); }});
  }
}
