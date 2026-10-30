import { Injectable, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { AccountApiService, TransactionApiService, apiError } from './api.services';
import { Account, Transaction } from './models';

@Injectable({ providedIn: 'root' })
export class BankingStore {
  private readonly accountsApi = inject(AccountApiService);
  private readonly transactionsApi = inject(TransactionApiService);
  readonly accounts = signal<Account[]>([]);
  readonly transactions = signal<Transaction[]>([]);
  readonly accountsLoading = signal(false);
  readonly transactionsLoading = signal(false);
  readonly accountsError = signal('');
  readonly transactionsError = signal('');
  readonly accountsLoaded = signal(false);
  readonly transactionsLoaded = signal(false);
  loadAccounts() {
    if (this.accountsLoading()) return;
    this.accountsLoading.set(true); this.accountsError.set('');
    this.accountsApi.list().pipe(finalize(() => this.accountsLoading.set(false))).subscribe({
      next: data => { this.accounts.set(data); this.accountsLoaded.set(true); },
      error: error => { this.accountsError.set(apiError(error)); this.accountsLoaded.set(false); }
    });
  }
  loadTransactions() {
    if (this.transactionsLoading()) return;
    this.transactionsLoading.set(true); this.transactionsError.set('');
    this.transactionsApi.list().pipe(finalize(() => this.transactionsLoading.set(false))).subscribe({
      next: data => { this.transactions.set(data); this.transactionsLoaded.set(true); },
      error: error => { this.transactionsError.set(apiError(error)); this.transactionsLoaded.set(false); }
    });
  }
  refresh() { this.loadAccounts(); this.loadTransactions(); }
}
