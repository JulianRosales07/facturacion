import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

export type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

@Component({
  selector: 'app-ui-badge',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span class="badge badge--{{ variant }} badge--{{ size }}">
      @if (dot) {
        <span class="badge__dot"></span>
      }
      <ng-content></ng-content>
    </span>
  `,
  styles: [`
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      font-weight: 500;
      border-radius: var(--cb-radius-pill, 9999px);
      line-height: 1;

      &--sm {
        padding: 0.2rem 0.5rem;
        font-size: 0.6875rem;
      }

      &--md {
        padding: 0.25rem 0.625rem;
        font-size: 0.75rem;
      }

      &--lg {
        padding: 0.375rem 0.75rem;
        font-size: 0.8125rem;
      }

      &__dot {
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background-color: currentColor;
      }

      &--success {
        background: var(--cb-success-bg, #ecfdf5);
        color: var(--cb-success, #059669);
        border: 1px solid var(--cb-success-border, #a7f3d0);
      }

      &--warning {
        background: var(--cb-warning-bg, #fffbeb);
        color: var(--cb-warning, #d97706);
        border: 1px solid var(--cb-warning-border, #fde68a);
      }

      &--danger {
        background: var(--cb-danger-bg, #fef2f2);
        color: var(--cb-danger, #dc2626);
        border: 1px solid var(--cb-danger-border, #fecaca);
      }

      &--info {
        background: var(--cb-info-bg, #eff6ff);
        color: var(--cb-info, #2563eb);
        border: 1px solid var(--cb-info-border, #bfdbfe);
      }

      &--neutral {
        background: var(--cb-bg-muted, #f1f5f9);
        color: var(--cb-text-secondary, #475569);
        border: 1px solid var(--cb-border-subtle, #e2e8f0);
      }
    }
  `]
})
export class UiBadgeComponent {
  @Input() variant: BadgeVariant = 'neutral';
  @Input() size: 'sm' | 'md' | 'lg' = 'md';
  @Input() dot = false;
}
