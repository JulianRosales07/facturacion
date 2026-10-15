import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

@Component({
  selector: 'app-ui-spinner',
  standalone: true,
  imports: [CommonModule, MatProgressSpinnerModule],
  template: `
    <div class="spinner-container" [class.spinner-container--overlay]="overlay">
      <mat-spinner [diameter]="diameter"></mat-spinner>
      @if (label) {
        <span class="spinner-container__label">{{ label }}</span>
      }
    </div>
  `,
  styles: [`
    .spinner-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 2rem;
      gap: 0.75rem;

      &--overlay {
        position: absolute;
        inset: 0;
        background: rgba(255, 255, 255, 0.75);
        z-index: 50;
        backdrop-filter: blur(2px);
      }

      &__label {
        font-size: 0.875rem;
        color: var(--cb-text-muted, #64748b);
        font-weight: 500;
      }
    }
  `]
})
export class UiSpinnerComponent {
  @Input() diameter = 36;
  @Input() label?: string;
  @Input() overlay = false;
}
