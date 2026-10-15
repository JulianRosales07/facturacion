import { Component, effect, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { WithholdingAuditService } from '../../../core/services/withholding-audit.service';
import {
  CHANNEL_LABELS, EVENT_LABELS, PaymentWithholdingAudit, SELECTION_STATUS_LABELS, SourceChannel, WithholdingAuditRow
} from '../../../core/models/withholding-audit.model';
import { CopCurrencyPipe } from '../../pipes/cop-currency.pipe';

/** Hora de Colombia: el backend entrega -05:00 y aquí se muestra en esa misma zona. */
export const BOGOTA_OFFSET = '-0500';

/**
 * "Auditoría de Retenciones" de un pago: qué se diligenció, cuándo, desde qué canal y quién, con
 * el historial de cambios. Solo lectura: la evidencia histórica no se edita.
 */
@Component({
  selector: 'app-withholding-audit-panel',
  standalone: true,
  imports: [DatePipe, DecimalPipe, CopCurrencyPipe],
  templateUrl: './withholding-audit-panel.component.html',
  styleUrls: ['./withholding-audit-panel.component.scss']
})
export class WithholdingAuditPanelComponent {
  private readonly api = inject(WithholdingAuditService);

  readonly paymentId = input.required<string>();
  readonly offset = BOGOTA_OFFSET;
  readonly eventLabels = EVENT_LABELS;
  readonly statusLabels = SELECTION_STATUS_LABELS;

  readonly audit = signal<PaymentWithholdingAudit | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly selected = signal<WithholdingAuditRow | null>(null);
  readonly exporting = signal(false);

  /** Excel con la auditoría de este pago (retenciones, historial y constancia de la exportación). */
  exportExcel(): void {
    this.exporting.set(true);
    this.api.export({ paymentId: this.paymentId() }, 'xlsx').subscribe({
      next: blob => {
        WithholdingAuditService.save(blob, WithholdingAuditService.filename('xlsx', '-pago-' + this.paymentId().slice(0, 8)));
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false)
    });
  }

  constructor() {
    effect(() => this.load(this.paymentId()));
  }

  load(paymentId: string): void {
    this.loading.set(true);
    this.error.set('');
    this.api.forPayment(paymentId).subscribe({
      next: audit => {
        this.audit.set(audit);
        this.selected.set(null);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('No fue posible consultar la auditoría de este pago.');
        this.loading.set(false);
      }
    });
  }

  channel(value: SourceChannel | null): string {
    return value ? CHANNEL_LABELS[value] : 'No registrado';
  }

  toggle(row: WithholdingAuditRow): void {
    this.selected.set(this.selected()?.id === row.id ? null : row);
  }
}
