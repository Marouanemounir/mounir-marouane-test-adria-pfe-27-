import { Routes } from '@angular/router';
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: 'dashboard', title: 'Dashboard · ADRIA', loadComponent: () => import('./pages/dashboard.component').then(m => m.DashboardComponent) },
  { path: 'accounts', title: 'Accounts · ADRIA', loadComponent: () => import('./pages/accounts.component').then(m => m.AccountsComponent) },
  { path: 'transactions', title: 'Transactions · ADRIA', loadComponent: () => import('./pages/transactions.component').then(m => m.TransactionsComponent) },
  { path: 'transfers', title: 'Transfers · ADRIA', loadComponent: () => import('./pages/transfers.component').then(m => m.TransfersComponent) },
  { path: 'assistant', title: 'AI Assistant · ADRIA', loadComponent: () => import('./pages/assistant.component').then(m => m.AssistantComponent) },
  { path: '**', redirectTo: 'dashboard' }
];
