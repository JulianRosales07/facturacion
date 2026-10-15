import { Component, OnInit, DestroyRef, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { timer, EMPTY } from 'rxjs';
import { catchError, exhaustMap } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SyncService } from '../../../../core/services/sync.service';
import { IncomingInvoiceService } from '../../../../core/services/incoming-invoice.service';
import { SyncStatusEntity } from '../../../../core/models/sync.model';
import { FileHistory, PageResult } from '../../../../core/models/incoming-invoice.model';

@Component({
  selector: 'app-sync-status',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './sync-status.component.html',
  styleUrls: ['../../../../shared/styles/received-pages.scss']
})
export class SyncStatusComponent implements OnInit {
  private readonly sync = inject(SyncService);
  private readonly invoices = inject(IncomingInvoiceService);
  private readonly destroy = inject(DestroyRef);

  readonly running = signal(false);
  readonly triggering = signal(false);
  readonly cycle = signal<SyncStatusEntity | null>(null);
  readonly history = signal<PageResult<FileHistory> | null>(null);
  readonly loadingHistory = signal(false);
  readonly error = signal('');
  readonly historyError = signal('');

  readonly pageSizes = [10, 25, 50, 100];
  readonly pageSize = signal(10);

  ngOnInit(): void {
    this.loadHistory();
    timer(0, 15000)
      .pipe(
        exhaustMap(() =>
          this.sync.getSyncStatus().pipe(
            catchError(() => {
              this.error.set('No se pudo consultar el estado de sincronización.');
              return EMPTY;
            })
          )
        ),
        takeUntilDestroyed(this.destroy)
      )
      .subscribe(data => {
        this.running.set(data.enEjecucion);
        this.cycle.set(data.ultimoCiclo || null);
        this.error.set('');
      });
  }

  refresh(): void {
    this.loadHistory(this.history()?.page || 0);
    this.sync
      .getSyncStatus()
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: data => {
          this.running.set(data.enEjecucion);
          this.cycle.set(data.ultimoCiclo || null);
          this.error.set('');
        },
        error: () => this.error.set('No se pudo consultar el estado.')
      });
  }

  loadHistory(page = 0): void {
    this.loadingHistory.set(true);
    this.historyError.set('');
    this.invoices
      .history(page, this.pageSize())
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: data => {
          this.history.set(data);
          this.loadingHistory.set(false);
        },
        error: () => {
          this.loadingHistory.set(false);
          this.historyError.set('No se pudo cargar el historial.');
        }
      });
  }

  changePageSize(size: number): void {
    this.pageSize.set(size);
    this.loadHistory(0);
  }

  /** Páginas (base 0) a mostrar: primera, última y vecinas de la actual; null marca un salto (…). */
  pageNumbers(current: number, total: number): (number | null)[] {
    if (total <= 7) return Array.from({ length: total }, (_, i) => i);
    const pages = new Set([0, total - 1, current - 1, current, current + 1].filter(p => p >= 0 && p < total));
    if (current <= 3) [1, 2, 3, 4].forEach(p => pages.add(p));
    if (current >= total - 4) [total - 5, total - 4, total - 3, total - 2].forEach(p => pages.add(p));
    const sorted = [...pages].sort((a, b) => a - b);
    return sorted.flatMap((p, i) => (i > 0 && p - sorted[i - 1] > 1 ? [null, p] : [p]));
  }

  trigger(): void {
    if (this.running() || this.triggering()) return;
    this.triggering.set(true);
    this.error.set('');
    this.sync
      .triggerManualSync()
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: status => {
          this.triggering.set(false);
          this.cycle.set(status);
          this.refresh();
        },
        error: err => {
          this.triggering.set(false);
          this.error.set(err?.error?.mensaje || 'No se recibió el resultado. Actualice el estado antes de volver a ejecutar.');
          this.loadHistory();
        }
      });
  }
}
