import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-ui-modal',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  template: `
    @if (isOpen) {
      <div
        class="modal-backdrop"
        role="presentation"
        (click)="onBackdropClick($event)"
      >
        <div
          class="modal-container modal-container--{{ size }}"
          role="dialog"
          aria-modal="true"
        >
          <div class="modal-header">
            <h3 class="modal-title">{{ title }}</h3>
            <button type="button" class="modal-close-btn" (click)="close()" aria-label="Cerrar">
              <mat-icon>close</mat-icon>
            </button>
          </div>
          <div class="modal-body">
            <ng-content></ng-content>
          </div>
          <div class="modal-footer">
            <ng-content select="[modal-footer]"></ng-content>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .modal-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(15, 23, 42, 0.45);
      backdrop-filter: blur(4px);
      z-index: 1000;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 1rem;
    }

    .modal-container {
      background: var(--cb-bg-surface, #ffffff);
      border-radius: var(--cb-radius-lg, 14px);
      box-shadow: var(--cb-shadow-lg);
      width: 100%;
      max-height: 90vh;
      display: flex;
      flex-direction: column;
      overflow: hidden;

      &--sm { max-width: 400px; }
      &--md { max-width: 580px; }
      &--lg { max-width: 800px; }
      &--xl { max-width: 1040px; }
    }

    .modal-header {
      padding: 1.25rem 1.5rem;
      border-bottom: 1px solid var(--cb-border-subtle, #e2e8f0);
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .modal-title {
      margin: 0;
      font-size: 1.125rem;
      font-weight: 600;
      color: var(--cb-text-primary, #0f172a);
    }

    .modal-close-btn {
      background: none;
      border: none;
      cursor: pointer;
      color: var(--cb-text-muted, #64748b);
      padding: 0.25rem;
      border-radius: 4px;
      display: flex;
      &:hover {
        background: var(--cb-bg-muted, #f1f5f9);
        color: var(--cb-text-primary, #0f172a);
      }
    }

    .modal-body {
      padding: 1.5rem;
      overflow-y: auto;
      flex: 1;
    }

    .modal-footer:has(*) {
      padding: 1rem 1.5rem;
      border-top: 1px solid var(--cb-border-subtle, #e2e8f0);
      background: var(--cb-bg-app, #f8fafc);
      display: flex;
      justify-content: flex-end;
      gap: 0.75rem;
    }
  `]
})
export class UiModalComponent {
  @Input() isOpen = false;
  @Input() title = '';
  @Input() size: 'sm' | 'md' | 'lg' | 'xl' = 'md';
  @Input() closeOnBackdrop = true;

  @Output() closed = new EventEmitter<void>();

  close(): void {
    this.closed.emit();
  }

  onBackdropClick(event: MouseEvent): void {
    if (this.closeOnBackdrop && event.target === event.currentTarget) {
      this.close();
    }
  }
}
