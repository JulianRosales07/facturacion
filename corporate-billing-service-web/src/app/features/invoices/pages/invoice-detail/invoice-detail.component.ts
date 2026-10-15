import { Component, OnInit, DestroyRef, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { NotificationService } from '../../../../core/services/notification.service';
import { debounceTime, filter, switchMap } from 'rxjs';
import { AdminRealtimeService } from '../../../../core/services/admin-realtime.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { InvoiceService } from '../../../../core/services/invoice.service';
import { FileDownloadService } from '../../../../core/services/file-download.service';
import { DetalleCobrosDto, FacturaDetalleDto } from '../../../../core/models/invoice.model';
import { FacturaSpPagosDto } from '../../../../core/models/sp-pagos.model';
import { PaymentService } from '../../../../core/services/payment.service';
import { PaymentDetail } from '../../../../core/models/payment.model';
import { CopCurrencyPipe } from '../../../../shared/pipes/cop-currency.pipe';
import { PaymentWithholdingsTableComponent } from '../../../../shared/components/payment-withholdings-table/payment-withholdings-table.component';
import { AppDatePipe } from '../../../../shared/pipes/app-date.pipe';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { WithholdingAuditPanelComponent } from '../../../../shared/components/withholding-audit-panel/withholding-audit-panel.component';
import { NoveltiesPanelComponent } from '../../../../shared/components/novelties-panel/novelties-panel.component';

@Component({
  selector: 'app-invoice-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, CopCurrencyPipe, AppDatePipe, HasPermissionDirective, WithholdingAuditPanelComponent, PaymentWithholdingsTableComponent, NoveltiesPanelComponent],
  templateUrl: './invoice-detail.component.html',
  styleUrls: ['../../../../shared/styles/received-pages.scss', '../invoice-list/invoice-list-grouped.scss']
})
export class InvoiceDetailComponent implements OnInit {
  private readonly api = inject(InvoiceService);
  private readonly route = inject(ActivatedRoute);
  private readonly downloads = inject(FileDownloadService);
  private readonly destroy = inject(DestroyRef);
  private readonly payments = inject(PaymentService);
  private readonly realtime = inject(AdminRealtimeService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);

  // Regeneración: nueva versión de la factura con las sedes y novedades actuales del periodo
  readonly showRegenerate = signal(false);
  readonly regenerating = signal(false);
  readonly needsCufeConfirmation = signal(false);
  regenerateReason = '';
  confirmTransmittedCufe = false;

  toggleRegenerate(): void { this.showRegenerate.update(open => !open); }

  regenerate(): void {
    const inv = this.invoice();
    const motivo = this.regenerateReason.trim();
    if (!inv) return;
    if (!motivo) { this.notifications.error('Indique el motivo de la regeneración.'); return; }
    this.regenerating.set(true);
    this.api.regenerateInvoice(inv.id, { motivo, confirmarCufeTransmitido: this.confirmTransmittedCufe })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: res => {
          this.regenerating.set(false);
          const current = res['facturaVigente'] as { id: string; numeroFactura: string; version: number } | undefined;
          if (!current) { this.notifications.error('El servidor no devolvió la nueva versión.'); return; }
          this.notifications.success(`Factura regenerada: ${current.numeroFactura} (versión ${current.version}).`);
          this.showRegenerate.set(false);
          this.regenerateReason = '';
          this.confirmTransmittedCufe = false;
          this.needsCufeConfirmation.set(false);
          void this.router.navigate(['/consultar-facturas', current.id]);
        },
        error: err => {
          this.regenerating.set(false);
          if (err?.error?.codigo === 'CUFE_YA_TRANSMITIDO') this.needsCufeConfirmation.set(true);
          this.notifications.error(err?.error?.mensaje || 'No se pudo regenerar la factura.');
        }
      });
  }

  readonly invoice = signal<FacturaDetalleDto | null>(null);
  readonly error = signal('');
  readonly loading = signal(true);

  // Pagos de la factura con el snapshot de retenciones con que se liquidaron (HU-54)
  readonly invoicePayments = signal<PaymentDetail[]>([]);
  readonly paymentsError = signal('');
  /** Pagos con la auditoría de retenciones desplegada. */
  readonly openAudits = signal<Set<string>>(new Set());

  toggleAudit(paymentId: string): void {
    this.openAudits.update(open => {
      const next = new Set(open);
      if (next.has(paymentId)) next.delete(paymentId); else next.add(paymentId);
      return next;
    });
  }

  // SP Pagos JSON modal & state
  readonly spPagosJson = signal<FacturaSpPagosDto | null>(null);
  readonly loadingJson = signal(false);
  readonly showJsonModal = signal(false);
  readonly jsonError = signal('');
  readonly copied = signal(false);

  ngOnInit(): void {
    // Si cambia esta factura o un pago que la incluye (o se reconecta), se vuelve a consultar por REST.
    this.realtime.refreshSignals().pipe(
      filter(signal => !!this.invoice() && AdminRealtimeService.concernsInvoice(signal, this.invoice()!.id)),
      debounceTime(400),
      takeUntilDestroyed(this.destroy)
    ).subscribe(() => this.reloadCurrent());
    this.route.paramMap.pipe(
      switchMap(params => {
        this.loading.set(true);
        this.error.set('');
        return this.api.getInvoiceById(params.get('id')!);
      }),
      takeUntilDestroyed(this.destroy)
    ).subscribe({
      next: invoice => {
        this.invoice.set(invoice);
        this.loading.set(false);
        this.loadPayments(invoice.id);
        this.loadChargeBreakdown(invoice);
      },
      error: err => {
        this.loading.set(false);
        this.error.set(err?.error?.mensaje || 'No se pudo consultar la factura.');
      }
    });
  }

  /** Tras reprocesar novedades: la factura y su detalle de cobros pueden haber cambiado. */
  onNoveltiesReprocessed(): void {
    this.reloadCurrent();
    const current = this.invoice();
    if (current) this.loadChargeBreakdown(current);
  }

  private reloadCurrent(): void {
    const current = this.invoice();
    if (!current) return;
    this.api.getInvoiceById(current.id).pipe(takeUntilDestroyed(this.destroy)).subscribe({ next: invoice => this.invoice.set(invoice) });
    this.loadPayments(current.id);
  }

  /** Detalle por bloques (deuda anterior, cargos fijos, otros conceptos) de cualquier factura. */
  readonly chargeBreakdown = signal<DetalleCobrosDto | null>(null);

  private loadChargeBreakdown(invoice: FacturaDetalleDto): void {
    this.chargeBreakdown.set(null);
    this.api.getChargeBreakdown(invoice.id).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: breakdown => this.chargeBreakdown.set(breakdown),
      error: () => this.chargeBreakdown.set(null)
    });
  }

  private loadPayments(invoiceId: string): void {
    this.paymentsError.set('');
    this.payments.getPaymentsForInvoice(invoiceId).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: list => this.invoicePayments.set(list),
      error: err => {
        this.invoicePayments.set([]);
        this.paymentsError.set(err?.error?.mensaje || 'No se pudieron consultar los pagos de la factura.');
      }
    });
  }

  download(): void {
    const inv = this.invoice();
    if (inv) this.downloads.download(`/facturas/${inv.id}/pdf`, `${inv.tipoFacturacion}-${inv.numeroFactura}.pdf`);
  }

  openSpPagosJson(): void {
    const inv = this.invoice();
    if (!inv) return;
    this.showJsonModal.set(true);
    this.loadingJson.set(true);
    this.jsonError.set('');
    this.copied.set(false);

    this.api.getSpPagosInvoice(inv.id).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: data => {
        this.spPagosJson.set(data);
        this.loadingJson.set(false);
      },
      error: err => {
        this.loadingJson.set(false);
        this.jsonError.set(err?.error?.mensaje || 'No se pudo generar el JSON para SP Pagos.');
      }
    });
  }

  closeJsonModal(): void {
    this.showJsonModal.set(false);
  }

  copyJson(): void {
    const data = this.spPagosJson();
    if (!data) return;
    const jsonStr = JSON.stringify(data, null, 2);
    void navigator.clipboard.writeText(jsonStr).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2500);
    });
  }
}
