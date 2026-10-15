import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-ui-card',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="card" [class.card--clickable]="clickable" [class.card--elevated]="elevated">
      @if (title || subtitle) {
        <div class="card__header">
          <div class="card__titles">
            @if (title) {
              <h3 class="card__title">{{ title }}</h3>
            }
            @if (subtitle) {
              <p class="card__subtitle">{{ subtitle }}</p>
            }
          </div>
          <div class="card__header-actions">
            <ng-content select="[card-header-actions]"></ng-content>
          </div>
        </div>
      }
      <div class="card__body">
        <ng-content></ng-content>
      </div>
      <div class="card__footer">
        <ng-content select="[card-footer]"></ng-content>
      </div>
    </div>
  `,
  styles: [`
    .card {
      background: var(--cb-bg-surface, #ffffff);
      border-radius: var(--cb-radius-md, 10px);
      border: 1px solid var(--cb-border-subtle, #e2e8f0);
      box-shadow: var(--cb-shadow-sm);
      overflow: hidden;
      display: flex;
      flex-direction: column;

      &--elevated {
        box-shadow: var(--cb-shadow-md);
      }

      &--clickable {
        cursor: pointer;
        transition: transform 0.15s ease, box-shadow 0.15s ease;
        &:hover {
          transform: translateY(-2px);
          box-shadow: var(--cb-shadow-md);
        }
      }

      &__header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 1.25rem 1.5rem;
        border-bottom: 1px solid var(--cb-border-subtle, #e2e8f0);
      }

      &__title {
        margin: 0;
        font-size: 1.125rem;
        font-weight: 600;
        color: var(--cb-text-primary, #0f172a);
      }

      &__subtitle {
        margin: 0.25rem 0 0;
        font-size: 0.8125rem;
        color: var(--cb-text-muted, #64748b);
      }

      &__body {
        padding: 1.5rem;
        flex: 1;
      }

      &__footer:has(*) {
        padding: 1rem 1.5rem;
        background: var(--cb-bg-app, #f8fafc);
        border-top: 1px solid var(--cb-border-subtle, #e2e8f0);
      }
    }
  `]
})
export class UiCardComponent {
  @Input() title?: string;
  @Input() subtitle?: string;
  @Input() elevated = false;
  @Input() clickable = false;
}
