import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-error-banner',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  template: `
    @if (visible) {
      <div class="error-banner">
        <mat-icon class="error-banner__icon">error_outline</mat-icon>
        <div class="error-banner__content">
          @if (title) {
            <div class="error-banner__title">{{ title }}</div>
          }
          <div class="error-banner__message">{{ message }}</div>
        </div>
        @if (dismissible) {
          <button type="button" (click)="dismiss()" class="error-banner__close" aria-label="Cerrar">
            <mat-icon>close</mat-icon>
          </button>
        }
      </div>
    }
  `,
  styles: [`
    .error-banner {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      padding: 0.875rem 1rem;
      background-color: var(--cb-danger-bg, #fef2f2);
      border: 1px solid var(--cb-danger-border, #fecaca);
      border-radius: var(--cb-radius-md, 10px);
      color: var(--cb-danger, #dc2626);
      margin-bottom: 1rem;

      &__icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
        flex-shrink: 0;
        margin-top: 1px;
      }

      &__content {
        flex: 1;
      }

      &__title {
        font-weight: 600;
        font-size: 0.875rem;
        margin-bottom: 0.25rem;
      }

      &__message {
        font-size: 0.8125rem;
        color: #991b1b;
      }

      &__close {
        background: none;
        border: none;
        padding: 0;
        cursor: pointer;
        color: inherit;
        opacity: 0.7;
        &:hover {
          opacity: 1;
        }
        mat-icon {
          font-size: 18px;
          width: 18px;
          height: 18px;
        }
      }
    }
  `]
})
export class ErrorBannerComponent {
  @Input() title?: string;
  @Input() message = 'Ha ocurrido un error al cargar la información.';
  @Input() dismissible = true;
  @Output() dismissed = new EventEmitter<void>();

  visible = true;

  dismiss(): void {
    this.visible = false;
    this.dismissed.emit();
  }
}
