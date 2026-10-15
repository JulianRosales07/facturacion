import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-empty-state',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  template: `
    <div class="empty-state">
      <div class="empty-state__icon-wrap">
        <mat-icon class="empty-state__icon">{{ icon }}</mat-icon>
      </div>
      <h4 class="empty-state__title">{{ title }}</h4>
      <p class="empty-state__description">{{ description }}</p>
      <div class="empty-state__actions">
        <ng-content select="[empty-actions]"></ng-content>
      </div>
    </div>
  `,
  styles: [`
    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      text-align: center;
      padding: 3rem 1.5rem;

      &__icon-wrap {
        width: 64px;
        height: 64px;
        border-radius: 50%;
        background-color: var(--cb-bg-muted, #f1f5f9);
        display: flex;
        align-items: center;
        justify-content: center;
        margin-bottom: 1rem;
      }

      &__icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
        color: var(--cb-text-muted, #64748b);
      }

      &__title {
        margin: 0 0 0.5rem;
        font-size: 1.125rem;
        font-weight: 600;
        color: var(--cb-text-primary, #0f172a);
      }

      &__description {
        margin: 0 0 1.5rem;
        font-size: 0.875rem;
        color: var(--cb-text-secondary, #475569);
        max-width: 400px;
      }

      &__actions {
        display: flex;
        gap: 0.75rem;
      }
    }
  `]
})
export class EmptyStateComponent {
  @Input() icon = 'inbox';
  @Input() title = 'No se encontraron registros';
  @Input() description = 'Aún no hay información disponible para este módulo.';
}
