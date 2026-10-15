import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-ui-toast',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  template: `
    @if (visible) {
      <div class="toast toast--{{ type }}">
        <mat-icon class="toast__icon">{{ getIcon() }}</mat-icon>
        <div class="toast__content">
          @if (title) {
            <span class="toast__title">{{ title }}</span>
          }
          <span class="toast__message">{{ message }}</span>
        </div>
        <button type="button" class="toast__close" (click)="dismiss()" aria-label="Cerrar">
          <mat-icon>close</mat-icon>
        </button>
      </div>
    }
  `,
  styles: [`
    .toast {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.875rem 1.25rem;
      border-radius: var(--cb-radius-md, 10px);
      box-shadow: var(--cb-shadow-lg);
      background: var(--cb-bg-surface, #ffffff);
      border-left: 4px solid;
      max-width: 420px;

      &--success {
        border-left-color: var(--cb-success, #059669);
        .toast__icon { color: var(--cb-success, #059669); }
      }

      &--error {
        border-left-color: var(--cb-danger, #dc2626);
        .toast__icon { color: var(--cb-danger, #dc2626); }
      }

      &--warning {
        border-left-color: var(--cb-warning, #d97706);
        .toast__icon { color: var(--cb-warning, #d97706); }
      }

      &--info {
        border-left-color: var(--cb-info, #2563eb);
        .toast__icon { color: var(--cb-info, #2563eb); }
      }

      &__content {
        display: flex;
        flex-direction: column;
        flex: 1;
      }

      &__title {
        font-size: 0.875rem;
        font-weight: 600;
        color: var(--cb-text-primary, #0f172a);
      }

      &__message {
        font-size: 0.8125rem;
        color: var(--cb-text-secondary, #475569);
      }

      &__close {
        background: none;
        border: none;
        cursor: pointer;
        color: var(--cb-text-muted, #64748b);
        padding: 0.25rem;
        display: flex;
        &:hover { color: var(--cb-text-primary, #0f172a); }
        mat-icon { font-size: 16px; width: 16px; height: 16px; }
      }
    }
  `]
})
export class UiToastComponent {
  @Input() type: 'success' | 'error' | 'warning' | 'info' = 'info';
  @Input() title?: string;
  @Input() message = '';
  @Input() visible = true;
  @Output() closed = new EventEmitter<void>();

  getIcon(): string {
    switch (this.type) {
      case 'success': return 'check_circle';
      case 'error': return 'error';
      case 'warning': return 'warning';
      default: return 'info';
    }
  }

  dismiss(): void {
    this.visible = false;
    this.closed.emit();
  }
}
