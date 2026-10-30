import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { environment } from '../../environments/environment';
import { Account, AccountStatus, BalanceResponse, ChatRequest, ChatResponse, CreateAccountRequest, CreateTransactionRequest, OperationResponse, Transaction, TransferRequest, TransferResponse } from './models';

@Injectable({ providedIn: 'root' })
export class AccountApiService {
  private readonly http = inject(HttpClient);
  private readonly url = environment.accountsUrl;
  list() { return this.http.get<Account[]>(this.url); }
  get(id: string) { return this.http.get<Account>(`${this.url}/${encodeURIComponent(id)}`); }
  balance(id: string) { return this.http.get<BalanceResponse>(`${this.url}/${encodeURIComponent(id)}/balance`); }
  create(body: CreateAccountRequest) { return this.http.post<Account>(this.url, body); }
  status(id: string, status: AccountStatus) {
    return this.http.patch<Account>(`${this.url}/${encodeURIComponent(id)}/status`, null, { params: { status } });
  }
}

@Injectable({ providedIn: 'root' })
export class TransactionApiService {
  private readonly http = inject(HttpClient);
  private readonly url = environment.transactionsUrl;
  list() { return this.http.get<Transaction[]>(this.url); }
  history(accountId: string) { return this.http.get<Transaction[]>(`${this.url}/account/${encodeURIComponent(accountId)}`); }
  create(body: CreateTransactionRequest, key: string) {
    return this.http.post<Transaction>(this.url, body, { headers: { 'Idempotency-Key': key } });
  }
  transfer(body: TransferRequest, key: string) {
    return this.http.post<TransferResponse>(`${this.url}/transfers`, body, { headers: { 'Idempotency-Key': key } });
  }
  operation(id: string) { return this.http.get<OperationResponse>(`${this.url}/operations/${encodeURIComponent(id)}`); }
}

@Injectable({ providedIn: 'root' })
export class BankingAgentApiService {
  private readonly http = inject(HttpClient);
  chat(body: ChatRequest) { return this.http.post<ChatResponse>(environment.agentUrl, body); }
}

export function apiError(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) return 'Une erreur inattendue est survenue.';
  if (error.status === 0 || error.status === 504 || error.status === 502) {
    const detail = error.error?.detail || error.error?.message;
    return `Service indisponible (HTTP ${error.status || 'réseau'}). ${detail || 'Vérifiez Gateway et les services backend, puis réessayez.'}`;
  }
  const body = error.error;
  const detail = typeof body === 'object' && body ? body.detail || body.message || body.error : '';
  const fields = Array.isArray(body?.errors) ? ` ${body.errors.join(' · ')}` : '';
  const recovery = body?.operationId ? ` Opération à vérifier : ${body.operationId}. Réessayez avec la même clé.` : '';
  const fallback = error.status === 401 ? 'Authentification de l’agent refusée. Vérifiez AGENT_AUTH_TOKEN côté proxy.' : error.status >= 500 ? 'Service indisponible. Vérifiez le backend et sa configuration.' : error.statusText;
  return `HTTP ${error.status} — ${detail || fallback}.${fields}${recovery}`;
}

// Only these explicit backend rejections prove that this request did not debit
// an account. Network, persistence and concurrency failures remain ambiguous.
export function isDefinitiveRejection(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse)) return false;
  const detail = error.error?.detail;
  if (error.status === 400 && typeof detail === 'string') return true;
  return error.status === 409 && ['Account is blocked', 'Insufficient balance', 'Incompatible currency'].includes(detail);
}
