// Public URLs only. Use a same-origin reverse proxy in production.
export const environment = {
  accountsUrl: '/api/accounts',
  transactionsUrl: '/api/transactions',
  agentUrl: '/api/chat'
} as const;
