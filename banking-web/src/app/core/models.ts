export type AccountStatus = 'ACTIVE' | 'BLOCKED';
export interface Account { id: string; customerReference: string; status: AccountStatus; currency: string; balance: number }
export interface CreateAccountRequest { customerReference: string; status: AccountStatus; currency: string; balance: number }
export interface BalanceResponse { id: string; balance: number }
export interface Transaction { id: string; accountId: string; amount: number; currency: string; merchantCategory: string; country: string; createdAt: string; operationId: string; transferId: string | null; direction: string }
export interface CreateTransactionRequest { accountId: string; amount: number; currency: string; merchantCategory: string; country: string }
export interface TransferRequest { sourceAccountId: string; destinationAccountId: string; amount: number; currency: string }
export interface TransferResponse extends TransferRequest { transferId: string; sourceBalance: number; destinationBalance: number; debitTransactionId: string; creditTransactionId: string }
export interface OperationResponse { operationId: string; type: string; state: string; debitTransactionId: string | null; creditTransactionId: string | null; createdAt: string }
export interface ChatRequest { message: string }
export interface ChatResponse { answer: string; toolsUsed: string[] }
