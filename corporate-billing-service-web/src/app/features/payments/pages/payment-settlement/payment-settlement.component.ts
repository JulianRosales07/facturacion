import { FileDownloadService } from '../../../../core/services/file-download.service';
import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { PaymentService } from '../../../../core/services/payment.service';
import { TaxService } from '../../../../core/services/tax.service';
import {
  ClientPortfolioSummary,
  InvoiceItemSummary,
  PaymentSimulationResult,
  PaymentResult,
  SimulatePaymentRequest,
  PaymentDeduction,
  RegisterPaymentRequest
} from '../../../../core/models/payment.model';
import { TaxConceptValidity } from '../../../../core/models/tax.model';
import { CopCurrencyPipe } from '../../../../shared/pipes/cop-currency.pipe';
import { AppDatePipe } from '../../../../shared/pipes/app-date.pipe';
import { UiCardComponent } from '../../../../shared/components/ui-card/ui-card.component';
import { UiButtonComponent } from '../../../../shared/components/ui-button/ui-button.component';
import { UiBadgeComponent } from '../../../../shared/components/ui-badge/ui-badge.component';
import { UiSpinnerComponent } from '../../../../shared/components/ui-spinner/ui-spinner.component';
import { DatePickerComponent } from '../../../../shared/components/calendar/date-picker.component';
import { NotificationService } from '../../../../core/services/notification.service';

@Component({
  selector: 'app-payment-settlement',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    CopCurrencyPipe,
    AppDatePipe,
    UiCardComponent,
    UiSpinnerComponent,
    DatePickerComponent
  ],
  templateUrl: './payment-settlement.component.html',
  styleUrls: ['./payment-settlement.component.scss']
})
export class PaymentSettlementComponent implements OnInit {
  private readonly downloads = inject(FileDownloadService);
  private readonly paymentService = inject(PaymentService);
  private readonly taxService = inject(TaxService);
  private readonly notification = inject(NotificationService);

  clientNit = '';
  readonly loadingPortfolio = signal<boolean>(false);
  readonly portfolio = signal<ClientPortfolioSummary | null>(null);

  // Selección de facturas
  readonly selectedInvoiceIds = signal<Set<string>>(new Set());

  // Catálogo de retenciones
  readonly availableValidities = signal<TaxConceptValidity[]>([]);
  readonly selectedValidityIds = signal<Set<string>>(new Set());

  // Simulación
  readonly isSimulating = signal<boolean>(false);
  readonly simulationResult = signal<PaymentSimulationResult | null>(null);

  // Registro de Pago
  paymentDate = new Date().toISOString().split('T')[0];
  paymentMethod = 'TRANSFERENCIA';
  bankAccount = '';
  /** Porcentaje que se practica en este pago por concepto (lo digita el analista según el cliente). */
  percentages: Record<string, number | null> = {};
  overrideAmounts: Record<string, number | null> = {};
  overrideReasons: Record<string, string> = {};
  private simulationSequence = 0;
  private requestKey = crypto.randomUUID();
  transactionNumber = '';
  observation = '';
  readonly isRegistering = signal<boolean>(false);
  readonly paymentSuccessResult = signal<PaymentResult | null>(null);

  emptySelection(): Set<string> { return new Set<string>(); }

  ngOnInit(): void {
    this.loadTaxValidities();
  }

  loadTaxValidities(): void {
    this.taxService.listValidities().subscribe({
      next: (validities) => {
        const active = validities.filter(v => v.active && v.validFrom <= this.paymentDate && (!v.validTo || v.validTo >= this.paymentDate));
        this.availableValidities.set(active);
        // El pagador decide qué conceptos practica; ninguno se impone por defecto.
        this.selectedValidityIds.set(new Set());
      },
      error: () => this.availableValidities.set([])
    });
  }

  searchPortfolio(): void {
    if (!this.clientNit.trim()) {
      this.notification.warning('Ingrese el NIT del cliente a consultar.');
      return;
    }

    ++this.simulationSequence;
    this.requestKey = crypto.randomUUID();
    this.loadingPortfolio.set(true);
    this.portfolio.set(null);
    this.simulationResult.set(null);
    this.paymentSuccessResult.set(null);
    this.selectedInvoiceIds.set(new Set());

    this.paymentService.getClientPortfolio(this.clientNit.trim()).subscribe({
      next: (data) => {
        this.portfolio.set(data);
        this.loadingPortfolio.set(false);
        // Seleccionar todas las facturas pendientes por defecto
        const allIds = new Set<string>();
        data.pendingInvoices?.forEach(inv => allIds.add(inv.invoiceId));
        this.selectedInvoiceIds.set(allIds);

        if (allIds.size > 0) {
          this.runSimulation();
        }
      },
      error: (err) => {
        this.loadingPortfolio.set(false);
        this.notification.error(err?.error?.mensaje || 'Error al consultar la cartera del cliente.');
      }
    });
  }

  toggleInvoice(invoiceId: string): void {
    const current = new Set(this.selectedInvoiceIds());
    if (current.has(invoiceId)) {
      current.delete(invoiceId);
    } else {
      current.add(invoiceId);
    }
    this.selectedInvoiceIds.set(current);
    this.runSimulation();
  }

  toggleValidity(validityId: string): void {
    const current = new Set(this.selectedValidityIds());
    if (current.has(validityId)) {
      current.delete(validityId);
    } else {
      current.add(validityId);
      this.percentages[validityId] = null;
    }
    this.selectedValidityIds.set(current);
    this.runSimulation();
  }

  private conceptSelections(): { conceptId: string; percentage: number }[] {
    return Array.from(this.selectedValidityIds()).map(id => ({ conceptId: id, percentage: this.percentages[id] as number }));
  }

  /** Cada retención marcada necesita un porcentaje mayor que 0 y dentro del máximo del catálogo. */
  percentageError(v: TaxConceptValidity): string | null {
    const value = this.percentages[v.id];
    if (value === null || value === undefined || Number.isNaN(value)) return 'Ingrese el porcentaje';
    if (value <= 0) return 'Debe ser mayor que 0';
    if (value > v.porcentajeMaximo) return `Máximo ${v.porcentajeMaximo}%`;
    return null;
  }

  private hasPercentageErrors(): boolean {
    return this.availableValidities().some(v => this.selectedValidityIds().has(v.id) && !!this.percentageError(v));
  }

  private deductions(): PaymentDeduction[] {
    return Array.from(this.selectedValidityIds()).filter(id => this.overrideAmounts[id] != null)
      .map(id => ({ validityId: id, amount: this.overrideAmounts[id]!, reason: this.overrideReasons[id] || '' }));
  }

  paymentDateChanged(): void {
    this.loadTaxValidities();
    this.selectedValidityIds.set(new Set());
    this.runSimulation();
  }

  runSimulation(): void {
    const sequence = ++this.simulationSequence;
    this.simulationResult.set(null);
    this.isSimulating.set(false);
    const invoiceIds = Array.from(this.selectedInvoiceIds());
    if (invoiceIds.length === 0 || this.hasPercentageErrors()) {
      this.simulationResult.set(null);
      return;
    }

    this.isSimulating.set(true);
    const req: SimulatePaymentRequest = {
      applicationMode: 'CONSOLIDATED',
      clientNit: this.portfolio()!.clientNit,
      invoiceIds,
      selectedConcepts: this.conceptSelections(),
      deductions: this.deductions(),
      paymentDate: this.paymentDate
    };

    this.paymentService.simulatePayment(req).subscribe({
      next: (res) => {
        if (sequence !== this.simulationSequence) return;
        this.simulationResult.set(res);
        this.isSimulating.set(false);
      },
      error: (err) => {
        if (sequence !== this.simulationSequence) return;
        this.notification.error(err?.error?.message || err?.error?.mensaje || 'No se pudo liquidar el pago. Revise los ajustes.');
        this.isSimulating.set(false);
      }
    });
  }

  submitPayment(): void {
    if (this.isRegistering() || this.isSimulating() || !this.simulationResult() || !this.bankAccount.trim()) return;
    const invoiceIds = Array.from(this.selectedInvoiceIds());
    if (invoiceIds.length === 0) {
      this.notification.warning('Seleccione al menos una factura para registrar el pago.');
      return;
    }

    if (!this.transactionNumber.trim()) {
      this.notification.warning('Ingrese el número de transacción o comprobante.');
      return;
    }

    this.isRegistering.set(true);
    const req: RegisterPaymentRequest = {
      applicationMode: 'CONSOLIDATED',
      clientNit: this.portfolio()!.clientNit,
      invoiceIds,
      selectedConcepts: this.conceptSelections(),
      paymentDate: this.paymentDate,
      paymentMethod: this.paymentMethod,
      bankAccount: this.bankAccount,
      transactionNumber: this.transactionNumber.trim(),
      observation: this.observation.trim() || undefined,
      idempotencyKey: this.requestKey,
      expectedNetAmount: this.simulationResult()!.netoAPagar,
      deductions: this.deductions()
    };

    this.paymentService.registerPayment(req).subscribe({
      next: (res) => {
        this.isRegistering.set(false);
        this.paymentSuccessResult.set(res);
        this.notification.success('Pago y retenciones aplicados exitosamente.');
      },
      error: (err) => {
        this.isRegistering.set(false);
        this.notification.error(err?.error?.mensaje || 'Error al registrar el pago');
      }
    });
  }

  downloadReceipt(): void {
    const pid = this.paymentSuccessResult()?.paymentId;
    if (pid) {
      this.downloads.download(`/pagos/${pid}/recibo`, `recibo-${pid}.html`);
    }
  }

  downloadSiigoFlatFile(): void {
    const pid = this.paymentSuccessResult()?.paymentId;
    if (pid) {
      this.downloads.download(this.paymentService.getSiigoPaymentFlatFileUrl(pid), `recaudo-${pid}.csv`);
    }
  }

  newPayment(): void {
    this.paymentSuccessResult.set(null);
    this.searchPortfolio();
  }
}
