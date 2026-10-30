import { Injectable } from '@angular/core';

export interface PendingOperation<T> { key: string; body: T; editable?: boolean }

// A submitted financial operation keeps its exact payload and key across retries,
// navigation and reloads in this tab. Never automatically retry a mutation.
@Injectable({ providedIn: 'root' })
export class PendingOperationService {
  private readonly memory = new Map<string, PendingOperation<unknown>>();
  read<T>(kind: string): PendingOperation<T> | null {
    try {
      const raw = sessionStorage.getItem(`adria.pending.${kind}`);
      if (raw) return JSON.parse(raw) as PendingOperation<T>;
    } catch { /* Memory fallback when browser storage is unavailable. */ }
    return (this.memory.get(kind) as PendingOperation<T> | undefined) || null;
  }
  begin<T>(kind: string, body: T): PendingOperation<T> {
    const existing = this.read<T>(kind);
    const reuse = existing && (!existing.editable || JSON.stringify(existing.body) === JSON.stringify(body));
    // Clear permission to edit BEFORE sending again: a previously rejected
    // request may now succeed or time out after committing.
    const operation = reuse ? { key: existing.key, body: existing.body, editable: false } : { key: crypto.randomUUID(), body, editable: false };
    this.memory.set(kind, operation);
    try { sessionStorage.setItem(`adria.pending.${kind}`, JSON.stringify(operation)); } catch { /* Keep memory copy. */ }
    return operation;
  }
  rejected(kind: string) {
    const existing = this.read<unknown>(kind);
    if (!existing) return;
    const updated = { ...existing, editable: true };
    this.memory.set(kind, updated);
    try { sessionStorage.setItem(`adria.pending.${kind}`, JSON.stringify(updated)); } catch { /* Memory copy. */ }
  }
  complete(kind: string) {
    this.memory.delete(kind);
    try { sessionStorage.removeItem(`adria.pending.${kind}`); } catch { /* No persistent entry. */ }
  }
}
