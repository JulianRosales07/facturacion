import { Component, Input, ContentChild, TemplateRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

export interface TableColumn {
  key: string;
  label: string;
  align?: 'left' | 'center' | 'right';
  width?: string;
}

@Component({
  selector: 'app-ui-table',
  standalone: true,
  imports: [CommonModule, MatProgressSpinnerModule],
  template: `
    <div class="table-container">
      <table class="table">
        <thead>
          <tr>
            @for (col of columns; track col.key) {
              <th [style.text-align]="col.align || 'left'" [style.width]="col.width">
                {{ col.label }}
              </th>
            }
          </tr>
        </thead>
        <tbody>
          @if (loading) {
            <tr>
              <td [attr.colspan]="columns.length" class="table__loading">
                <mat-spinner diameter="32"></mat-spinner>
                <span>Cargando información...</span>
              </td>
            </tr>
          } @else if (!data || data.length === 0) {
            <tr>
              <td [attr.colspan]="columns.length" class="table__empty">
                {{ emptyText }}
              </td>
            </tr>
          } @else {
            @for (item of data; track $index) {
              <tr>
                @if (rowTemplate) {
                  <ng-container
                    *ngTemplateOutlet="rowTemplate; context: { $implicit: item, index: $index }"
                  ></ng-container>
                } @else {
                  @for (col of columns; track col.key) {
                    <td [style.text-align]="col.align || 'left'">
                      {{ getCellValue(item, col.key) }}
                    </td>
                  }
                }
              </tr>
            }
          }
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .table-container {
      width: 100%;
      overflow-x: auto;
      border: 1px solid var(--cb-border-subtle, #e2e8f0);
      border-radius: var(--cb-radius-md, 10px);
      background: var(--cb-bg-surface, #ffffff);
    }

    .table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.875rem;

      th {
        background: var(--cb-bg-app, #f8fafc);
        color: var(--cb-text-secondary, #475569);
        font-weight: 600;
        padding: 0.875rem 1rem;
        border-bottom: 1px solid var(--cb-border-subtle, #e2e8f0);
        white-space: nowrap;
      }

      td {
        padding: 0.875rem 1rem;
        border-bottom: 1px solid var(--cb-border-subtle, #e2e8f0);
        color: var(--cb-text-primary, #0f172a);
      }

      tbody tr:last-child td {
        border-bottom: none;
      }

      tbody tr:hover:not(:has(.table__empty, .table__loading)) {
        background: var(--cb-bg-muted, #f8fafc);
      }

      &__loading, &__empty {
        text-align: center !important;
        padding: 2.5rem 1rem !important;
        color: var(--cb-text-muted, #64748b);
      }

      &__loading {
        display: table-cell;
        vertical-align: middle;
        mat-spinner {
          display: inline-block;
          vertical-align: middle;
          margin-right: 0.5rem;
        }
      }
    }
  `]
})
export class UiTableComponent<T extends object> {
  @Input() columns: TableColumn[] = [];
  @Input() data: T[] = [];
  @Input() loading = false;
  @Input() emptyText = 'No hay datos disponibles';

  @ContentChild('rowTemplate') rowTemplate?: TemplateRef<unknown>;

  getCellValue(item: T, key: string): unknown {
    return (item as Record<string, unknown>)[key];
  }
}
