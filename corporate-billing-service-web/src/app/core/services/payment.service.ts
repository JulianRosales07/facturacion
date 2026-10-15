import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ApiService } from './api.service';
import {
  ClientPortfolioSummary,
  PaymentSimulationResult,
  PaymentResult,
  SimulatePaymentRequest,
  RegisterPaymentRequest,
  PaymentDetail
} from '../models/payment.model';

@Injectable({
  providedIn: 'root'
})
export class PaymentService {
  private readonly api = inject(ApiService);

  /**
   * Sesión de selección de retenciones del analista (auditoría): agrupa lo que selecciona, cambia o
   * quita en una misma liquidación. Se renueva al registrar el pago. La hora la fija el servidor.
   */
  private selectionSession = PaymentService.newSelectionSession();

  static newSelectionSession(): string {
    return globalThis.crypto?.randomUUID?.() ?? `sel-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
  }

  get currentSelectionSession(): string {
    return this.selectionSession;
  }

  private auditHeaders(): Record<string, string> {
    return { 'X-Client-Channel': 'ADMIN_WEB', 'X-Selection-Session': this.selectionSession };
  }

  getClientPortfolio(nit: string): Observable<ClientPortfolioSummary> {
    return this.api.get<ClientPortfolioSummary>(`/pagos/cartera/${nit}`);
  }

  simulatePayment(request: SimulatePaymentRequest): Observable<PaymentSimulationResult> {
    return this.api.post<PaymentSimulationResult>('/pagos/simular', request, { headers: this.auditHeaders() });
  }

  registerPayment(request: RegisterPaymentRequest): Observable<PaymentResult> {
    return this.api.post<PaymentResult>('/pagos', request, { headers: this.auditHeaders() }).pipe(
      // Otra liquidación es otro proceso: su historial de retenciones empieza de cero.
      tap(() => this.selectionSession = PaymentService.newSelectionSession())
    );
  }

  getPayment(id: string): Observable<PaymentDetail> {
    return this.api.get<PaymentDetail>(`/pagos/${id}`);
  }

  /** Pagos que incluyen la factura, con el snapshot de retenciones con que se liquidaron. */
  getPaymentsForInvoice(invoiceId: string): Observable<PaymentDetail[]> {
    return this.api.get<PaymentDetail[]>(`/pagos/factura/${invoiceId}`);
  }

  getReceiptHtml(id: string): Observable<string> {
    return this.api.getText(`/pagos/${id}/recibo`);
  }

  getSiigoPaymentFlatFileUrl(id: string): string {
    return `/api/v1/pagos/${id}/interfaz-siigo`;
  }
}
