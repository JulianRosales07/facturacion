import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { WithholdingAuditService } from '../../../../core/services/withholding-audit.service';
import { NotificationService } from '../../../../core/services/notification.service';
import {
  CHANNEL_LABELS, SELECTION_STATUS_LABELS, SourceChannel, WithholdingAuditFilter, WithholdingAuditRow
} from '../../../../core/models/withholding-audit.model';
import { CopCurrencyPipe } from '../../../../shared/pipes/cop-currency.pipe';
import { UiModalComponent } from '../../../../shared/components/ui-modal/ui-modal.component';
import {
  BOGOTA_OFFSET, WithholdingAuditPanelComponent
} from '../../../../shared/components/withholding-audit-panel/withholding-audit-panel.component';

const EMPTY_FILTER: WithholdingAuditFilter = { nit: '', invoice: '', paymentId: '', concept: '', user: '', channel: '', from: '', to: '' };

/**
 * Búsqueda de la auditoría de retenciones: responde quién diligenció qué porcentaje, cuándo,
 * desde qué canal, sobre qué pago o factura y cuánto se retuvo. Todo es evidencia guardada.
 */
@Component({
  selector: 'app-withholding-audit',
  standalone: true,
  imports: [FormsModule, DatePipe, DecimalPipe, CopCurrencyPipe, UiModalComponent, WithholdingAuditPanelComponent],
  templateUrl: './withholding-audit.component.html',
  styleUrls: ['../../../admin/admin-page.scss']
})
export class WithholdingAuditComponent implements OnInit {
  private readonly api = inject(WithholdingAuditService);
  private readonly notification = inject(NotificationService);

  readonly pageSizes = [10, 25, 50, 100];
  readonly pageSize = signal(10);
  readonly offset = BOGOTA_OFFSET;
  readonly channels = Object.entries(CHANNEL_LABELS) as [SourceChannel, string][];
  readonly statusLabels = SELECTION_STATUS_LABELS;

  filter: WithholdingAuditFilter = { ...EMPTY_FILTER };
  readonly rows = signal<WithholdingAuditRow[]>([]);
  readonly loading = signal(true);
  readonly exporting = signal<'xlsx' | 'csv' | null>(null);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly totalItems = signal(0);
  readonly detailPaymentId = signal<string | null>(null);

  ngOnInit(): void {
    this.search();
  }

  channel(value: SourceChannel | null): string {
    return value ? CHANNEL_LABELS[value] : 'No registrado';
  }

  search(page = 0): void {
    if (this.filter.from && this.filter.to && this.filter.to < this.filter.from) {
      this.notification.warning('La fecha hasta no puede ser anterior a la fecha desde.');
      return;
    }
    this.loading.set(true);
    this.page.set(page);
    this.api.search(this.filter, page, this.pageSize()).subscribe({
      next: result => {
        this.rows.set(result.items);
        this.totalPages.set(result.totalPages);
        this.totalItems.set(result.totalItems);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  changePageSize(size: number): void {
    this.pageSize.set(size);
    this.search(0);
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

  clear(): void {
    this.filter = { ...EMPTY_FILTER };
    this.search();
  }

  goTo(page: number): void {
    if (page >= 0 && page < this.totalPages()) this.search(page);
  }

  /** Exporta con los filtros aplicados en pantalla. */
  export(format: 'xlsx' | 'csv'): void {
    this.exporting.set(format);
    this.api.export(this.filter, format).subscribe({
      next: blob => {
        WithholdingAuditService.save(blob, WithholdingAuditService.filename(format));
        this.exporting.set(null);
      },
      error: () => this.exporting.set(null)
    });
  }
}
