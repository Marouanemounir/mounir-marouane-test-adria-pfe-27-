import { afterEveryRender, Component, DestroyRef, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { BankingAgentApiService, apiError } from '../core/api.services';
import { IconComponent } from '../shared/icon.component';

interface Message { id: number; role: 'user' | 'assistant'; text: string; tools?: string[] }
@Component({
  selector: 'app-assistant', imports: [ReactiveFormsModule, IconComponent],
  template: `
    <div class="page-heading"><div><p class="eyebrow">VOTRE COPILOTE BANCAIRE</p><h1>AI Assistant</h1><p>Posez vos questions à votre Banking Agent.</p></div><span class="assistant-label"><app-icon name="spark"/>Banking intelligence</span></div>
    <section class="chat-panel panel"><div class="chat-header"><span class="assistant-avatar"><app-icon name="spark"/></span><div><h2>ADRIA Assistant</h2><p>Connecté à votre API Banking Agent</p></div><span class="badge">MCP + LLM</span></div>
    <div class="chat-messages" #messageList aria-live="polite" role="log" aria-label="Conversation">
    @if (messages().length === 0) { <div class="chat-welcome"><span class="chat-welcome-icon"><app-icon name="spark"/></span><h2>Comment puis-je vous aider ?</h2><p>Explorez votre activité bancaire à l’aide de votre assistant.</p><div class="suggestion-grid">@for (suggestion of suggestions; track suggestion) { <button (click)="useSuggestion(suggestion)"><app-icon name="arrow"/>{{ suggestion }}</button> }</div><small>Les réponses proviennent exclusivement de votre service IA.</small></div> }
    @for (message of messages(); track message.id) { <div class="message-row" [class.from-user]="message.role === 'user'"><span class="message-avatar"><app-icon [name]="message.role === 'user' ? 'user' : 'spark'"/></span><div class="message-content"><small>{{ message.role === 'user' ? 'Vous' : 'ADRIA Assistant' }}</small><div class="message-bubble">{{ message.text }}</div>@if (message.tools?.length) { <small class="tools-used">Outils utilisés : {{ message.tools?.join(', ') }}</small> }</div></div> }
    @if (busy()) { <div class="message-row"><span class="message-avatar"><app-icon name="spark"/></span><div class="typing" role="status"><i></i><i></i><i></i><span>L’assistant consulte les services…</span></div></div> }
    </div>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}<br>Le message reste disponible ci-dessous pour réessayer.</div> }
    <form class="chat-composer" [formGroup]="form" (ngSubmit)="send()"><label class="sr-only" for="chat-input">Votre message</label><textarea id="chat-input" formControlName="message" rows="2" maxlength="4000" placeholder="Posez votre question sur vos comptes…" (keydown)="onKeydown($event)"></textarea><div class="composer-bottom"><span>{{ form.controls.message.value.length }}/4000 · Entrée pour envoyer</span><button class="btn btn-primary" [disabled]="busy() || form.invalid"><app-icon name="send"/>{{ busy() ? 'En cours…' : 'Envoyer' }}</button></div></form>
    <p class="chat-disclaimer">Les informations fournies par l’IA doivent être vérifiées avant toute décision.</p></section>`
})
export class AssistantComponent {
  private readonly api = inject(BankingAgentApiService); private readonly fb = inject(FormBuilder); private readonly destroyRef = inject(DestroyRef);
  readonly messages = signal<Message[]>([]); readonly busy = signal(false); readonly error = signal('');
  readonly messageList = viewChild<ElementRef<HTMLElement>>('messageList');
  private scrollPending = false; private lastFailedMessage = '';
  readonly suggestions = ['Quels sont mes comptes ?', 'Quel est le solde de mes comptes ?', 'Affiche mes transactions récentes.'];
  readonly form = this.fb.nonNullable.group({message: ['', [Validators.required, Validators.maxLength(4000), Validators.pattern(/\S/)]]});
  constructor() { afterEveryRender(() => { if (this.scrollPending) { const el = this.messageList()?.nativeElement; if (el) el.scrollTop = el.scrollHeight; this.scrollPending = false; } }); }
  useSuggestion(value: string) { this.form.controls.message.setValue(value); }
  onKeydown(event: KeyboardEvent) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); this.send(); } }
  send() {
    if (this.form.invalid || this.busy()) return;
    const message = this.form.controls.message.value.trim();
    if (this.lastFailedMessage !== message) this.messages.update(items => [...items, {id: Date.now(), role: 'user', text: message}]);
    this.busy.set(true); this.error.set(''); this.scrollPending = true; this.form.controls.message.disable();
    this.api.chat({message}).pipe(finalize(() => { this.busy.set(false); this.form.controls.message.enable(); }), takeUntilDestroyed(this.destroyRef)).subscribe({next: response => { this.messages.update(items => [...items, {id: Date.now(), role: 'assistant', text: response.answer, tools: response.toolsUsed}]); this.form.reset(); this.lastFailedMessage = ''; this.scrollPending = true; }, error: e => { this.error.set(apiError(e)); this.lastFailedMessage = message; }});
  }
}
