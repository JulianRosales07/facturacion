import { Component, input, output } from '@angular/core';
import { UiModalComponent } from '../ui-modal/ui-modal.component';

/** Confirmación previa a una acción delicada (desactivar, eliminar). */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [UiModalComponent],
  template: `
    <app-ui-modal [isOpen]="open()" [title]="title()" size="sm" (closed)="cancelled.emit()">
      <p class="confirm-message">{{ message() }}</p>
      @if (detail()) {
        <p class="confirm-detail">{{ detail() }}</p>
      }
      <div modal-footer class="confirm-actions">
        <button type="button" class="btn btn--outline" (click)="cancelled.emit()" [disabled]="busy()">Cancelar</button>
        <button type="button" class="btn" [class.btn--primary-red]="danger()" [class.btn--outline]="!danger()"
                (click)="confirmed.emit()" [disabled]="busy()">
          {{ busy() ? 'Procesando…' : confirmLabel() }}
        </button>
      </div>
    </app-ui-modal>
  `,
  styles: [`
    .confirm-message { margin: 0; font-size: 0.875rem; color: var(--tx-0f172a); line-height: 1.5; }
    .confirm-detail { margin: 0.6rem 0 0; font-size: 0.78rem; color: var(--tx-b45309); line-height: 1.45; }
    .confirm-actions { display: flex; justify-content: flex-end; gap: 0.5rem; width: 100%; }
  `]
})
export class ConfirmDialogComponent {
  readonly open = input(false);
  readonly title = input('Confirmar');
  readonly message = input('');
  readonly detail = input<string | null>(null);
  readonly confirmLabel = input('Confirmar');
  readonly danger = input(true);
  readonly busy = input(false);
  readonly confirmed = output<void>();
  readonly cancelled = output<void>();
}
