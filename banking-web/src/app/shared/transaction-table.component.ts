import { Component, input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Transaction } from '../core/models';

@Component({
  selector: 'app-transaction-table',
  imports: [DatePipe, DecimalPipe],
  template: `
    <div class="table-scroll"><table><thead><tr><th>Opération / compte</th><th>Date</th><th>Pays</th><th>Direction</th><th class="align-right">Montant</th></tr></thead>
      <tbody>@for (item of transactions(); track item.id) {
        <tr><td><strong>{{ item.merchantCategory }}</strong><small class="mono" [title]="item.accountId">{{ item.accountId.slice(0, 8) }}…</small></td>
        <td>{{ item.createdAt | date:'dd/MM/yyyy HH:mm' }}</td><td>{{ item.country }}</td>
        <td><span class="badge" [class.positive]="item.direction === 'CREDIT'">{{ item.direction }}</span></td>
        <td class="align-right amount" [class.credit]="item.direction === 'CREDIT'">{{ item.direction === 'CREDIT' ? '+' : '−' }}{{ item.amount | number:'1.2-2' }} <span>{{ item.currency }}</span></td></tr>
      } @empty { <tr><td colspan="5" class="empty">Aucune transaction à afficher.</td></tr> }</tbody></table></div>`
})
export class TransactionTableComponent { readonly transactions = input.required<Transaction[]>(); }
