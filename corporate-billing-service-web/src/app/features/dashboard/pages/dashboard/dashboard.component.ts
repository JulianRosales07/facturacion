import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, DestroyRef, inject, signal, computed } from '@angular/core';
import { RouterModule } from '@angular/router';
import { forkJoin } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { IncomingInvoiceService } from '../../../../core/services/incoming-invoice.service';
import { SyncService } from '../../../../core/services/sync.service';
import { IncomingInvoice, InvoiceSummary } from '../../../../core/models/incoming-invoice.model';
import { SyncStatusEntity } from '../../../../core/models/sync.model';
import { CopCurrencyPipe } from '../../../../shared/pipes/cop-currency.pipe';
import { AppDatePipe } from '../../../../shared/pipes/app-date.pipe';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterModule, CopCurrencyPipe, AppDatePipe],
  templateUrl: './dashboard.component.html',
  styleUrls: [
    '../../../../shared/styles/received-pages.scss',
    './dashboard.component.scss'
  ]
})
export class DashboardComponent implements OnInit {
  private readonly invoices = inject(IncomingInvoiceService);
  private readonly sync = inject(SyncService);
  private readonly destroy = inject(DestroyRef);

  readonly summary = signal<InvoiceSummary | null>(null);
  readonly recent = signal<IncomingInvoice[]>([]);
  readonly cycle = signal<SyncStatusEntity | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');

  readonly pdfCoverage = computed(() => {
    const s = this.summary();
    if (!s || !s.totalInvoices) return 0;
    return Math.round((s.withPdf / s.totalInvoices) * 100);
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    forkJoin({
      summary: this.invoices.summary(),
      recent: this.invoices.list({ page: 0, size: 6 }),
      sync: this.sync.getSyncStatus()
    })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          this.summary.set(data.summary);
          this.recent.set(data.recent.items);
          this.cycle.set(data.sync.ultimoCiclo || null);
          this.loading.set(false);
        },
        error: (error: HttpErrorResponse) => {
          this.loading.set(false);
          this.summary.set(null);
          this.recent.set([]);
          this.cycle.set(null);
          this.error.set(
            error.status === 401
              ? 'El servidor requiere acceso corporativo para consultar las facturas. La vista local no tiene una sesión autenticada.'
              : error.status === 403
              ? 'Tu usuario no tiene permisos para consultar este resumen.'
              : error.status === 0
              ? 'No se pudo conectar con el servidor. Comprueba que el backend esté disponible.'
              : 'No se pudo cargar el resumen. Intenta nuevamente.'
          );
        }
      });
  }
}
