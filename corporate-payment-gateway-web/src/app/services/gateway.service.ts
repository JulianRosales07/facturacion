import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, throwError, catchError, map, delay, switchMap, of } from 'rxjs';
import { CheckoutResult, ClientInvoiceGroup, ClientRetentionConfig, ConceptSelectionPayload, RetentionApplicationMode, PaymentForm, PaymentSettlement, RetentionSelectionPayload, TaxCatalogConcept, PaymentTransactionReceipt, PseBank } from '../models/gateway.model';

@Injectable({
  providedIn: 'root'
})
export class GatewayService {
  private readonly http = inject(HttpClient);

  /**
   * Sesión de selección de retenciones (auditoría): agrupa en el backend lo que el cliente
   * selecciona, cambia o quita durante un mismo proceso de pago. Se renueva tras cada pago.
   * La fecha y hora las registra el servidor, nunca el navegador.
   */
  private selectionSession = GatewayService.newSelectionSession();

  static newSelectionSession(): string {
    return globalThis.crypto?.randomUUID?.() ?? `sel-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
  }

  get currentSelectionSession(): string {
    return this.selectionSession;
  }

  /** Canal y sesión para la auditoría de retenciones. */
  private auditHeaders(): HttpHeaders {
    return new HttpHeaders({ 'X-Client-Channel': 'PAYMENT_GATEWAY', 'X-Selection-Session': this.selectionSession });
  }

  readonly popularBanks: PseBank[] = [
    { code: '1007', name: 'Bancolombia', popular: true },
    { code: '1001', name: 'Banco de Bogotá', popular: true },
    { code: '1051', name: 'Davivienda', popular: true },
    { code: '1013', name: 'BBVA Colombia', popular: true },
    { code: '1507', name: 'Nequi', popular: true },
    { code: '1023', name: 'Banco de Occidente' },
    { code: '1019', name: 'Scotiabank Colpatria' },
    { code: '1002', name: 'Banco Popular' },
    { code: '1032', name: 'Banco Caja Social' },
    { code: '1040', name: 'Banco Agrario de Colombia' },
    { code: '1052', name: 'Banco AV Villas' },
    { code: '1066', name: 'Banco Cooperativo Coopcentral' },
    { code: '1060', name: 'Banco Pichincha' },
    { code: '1062', name: 'Banco Falabella' },
    { code: '1061', name: 'Banco Serfinanza' },
    { code: '1059', name: 'Bancamía' },
    { code: '1070', name: 'Nu Colombia (Nu Bank)' },
    { code: '1551', name: 'Daviplata' },
    { code: '1552', name: 'Dale' },
    { code: '1058', name: 'Lulo Bank' }
  ];

  private getPaidKeys(): Set<string> {
    try {
      const raw = localStorage.getItem('cp_paid_invoice_keys');
      return raw ? new Set(JSON.parse(raw)) : new Set();
    } catch {
      return new Set();
    }
  }

  markInvoicesAsPaid(keys: string[]): void {
    const set = this.getPaidKeys();
    keys.forEach(k => set.add(k));
    localStorage.setItem('cp_paid_invoice_keys', JSON.stringify(Array.from(set)));
  }

  clearPaidKeys(): void {
    localStorage.removeItem('cp_paid_invoice_keys');
  }

  /**
   * Consulta el JSON consolidado de facturas asociadas a un NIT.
   * Consulta el backend (que excluye facturas en estado PAGADA).
   * Si no hay facturas pendientes, emite error específico para notificar al usuario.
   */
  getInvoicesByNit(nit: string): Observable<ClientInvoiceGroup> {
    const cleanNit = nit.trim().replace(/\s+/g, '');

    // 1. Consultar endpoint consolidado por NIT disponible en el backend (fuente de verdad).
    // origen=TODAS: además de las de Siigo, la factura interna del mes con sus novedades se cobra de una vez.
    return this.http.get<{ items: ClientInvoiceGroup[] }>(`/api/v1/facturas/recibidas/por-nit?search=${cleanNit}&origen=TODAS`).pipe(
      map(res => {
        if (res && res.items && res.items.length > 0) {
          const found = res.items.find(g => g.nit.replace(/\s+/g, '') === cleanNit) || res.items[0];
          if (found && found.facturas && found.facturas.length > 0) {
            return {
              ...found,
              facturas: found.facturas.map(f => ({ ...f, seleccionada: true }))
            };
          }
        }
        throw new Error('NO_PENDING_INVOICES');
      }),
      catchError(err => {
        if (err?.message === 'NO_PENDING_INVOICES') {
          throw err;
        }
        // 2. Intentar endpoint directo por path variable
        return this.http.get<ClientInvoiceGroup>(`/api/v1/facturas/recibidas/por-nit/${cleanNit}`).pipe(
          map(found => {
            if (found && found.facturas && found.facturas.length > 0) {
              return {
                ...found,
                facturas: found.facturas.map(f => ({ ...f, seleccionada: true }))
              };
            }
            throw new Error('NO_PENDING_INVOICES');
          }),
          catchError(innerErr => {
            // 404 = el NIT no tiene facturas pendientes. Cualquier otro error es de conexión y se
            // propaga: mostrar facturas demo permitiría "pagar" facturas que no existen en el backend.
            if (innerErr?.status === 404) {
              throw new Error('NO_PENDING_INVOICES');
            }
            throw innerErr;
          })
        );
      })
    );
  }

  /** Catálogo general de retenciones vigentes. La selección es opcional y nunca se preselecciona. */
  getTaxCatalog(): Observable<TaxCatalogConcept[]> {
    return this.http.get<TaxCatalogConcept[]>('/api/v1/impuestos/conceptos');
  }

  /**
   * PDF oficial de UNA factura (su propio servicio), identificado por su id; nunca por NIT ni el
   * consolidado. Se pide por HttpClient como Blob para que viaje la autenticación del cliente HTTP.
   */
  getInvoicePdf(invoiceId: string, disposition: 'inline' | 'attachment' = 'inline'): Observable<Blob> {
    return this.http.get(`/api/v1/facturas/${encodeURIComponent(invoiceId)}/pdf`, {
      params: { disposition },
      responseType: 'blob'
    });
  }

  /** Nombre de archivo de la factura: F1-51980.pdf (sin caracteres problemáticos). */
  static pdfFilename(invoice: { tipo: string; consecutivo: string }): string {
    const safe = (value: string) => String(value ?? '').trim().replace(/[^A-Za-z0-9_-]+/g, '_');
    return `${safe(invoice.tipo)}-${safe(invoice.consecutivo)}.pdf`;
  }

  /** Configuración autorizada por el cliente; null si nunca autorizó guardarla. */
  getRetentionConfig(nit: string): Observable<ClientRetentionConfig | null> {
    return this.http.get<ClientRetentionConfig>(`/api/v1/clientes/${encodeURIComponent(nit)}/configuracion-retenciones`).pipe(
      catchError(err => err?.status === 404 ? of(null) : throwError(() => err))
    );
  }

  /** Guarda la configuración solo tras la aceptación expresa del cliente en el modal de autorización. */
  saveRetentionConfig(nit: string, applicationMode: RetentionApplicationMode,
                      selectedConcepts: ConceptSelectionPayload[]): Observable<ClientRetentionConfig> {
    return this.http.put<ClientRetentionConfig>(`/api/v1/clientes/${encodeURIComponent(nit)}/configuracion-retenciones`,
      { autorizado: true, applicationMode, selectedConcepts });
  }

  deleteRetentionConfig(nit: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/clientes/${encodeURIComponent(nit)}/configuracion-retenciones`);
  }

  /**
   * Liquidación oficial calculada por el backend. El frontend no calcula retenciones: solo envía
   * qué facturas y qué conceptos eligió el cliente.
   */
  simulate(nit: string, selection: RetentionSelectionPayload): Observable<PaymentSettlement> {
    return this.http.post<PaymentSettlement>('/api/v1/pagos/simular', { nit, ...selection }, { headers: this.auditHeaders() });
  }

  /**
   * Procesa el pago:
   * 1. checkout: el backend recalcula la liquidación y crea el pago PENDIENTE (las facturas no cambian);
   * 2. la entidad financiera cobra el netoAPagar devuelto por el backend (simulado en este portal);
   * 3. confirmación: el backend aplica el pago y solo entonces las facturas pasan a PAGADA.
   */
  processPayment(
    group: ClientInvoiceGroup,
    selectedInvoices: ClientInvoiceGroup['facturas'],
    selection: RetentionSelectionPayload,
    form: PaymentForm
  ): Observable<PaymentTransactionReceipt> {
    const randomCus = Math.floor(100000000 + Math.random() * 900000000).toString();
    const randomAuth = 'AUTH-' + Math.floor(100000 + Math.random() * 900000);

    let bankOrCard = 'PSE - Débito en Cuenta';
    if (form.method === 'PSE') {
      const bank = this.popularBanks.find(b => b.code === form.bankCode);
      bankOrCard = bank ? `PSE - ${bank.name}` : 'PSE - Débito en Cuenta';
    } else if (form.method === 'CARD') {
      const last4 = form.cardNumber ? form.cardNumber.replace(/\s+/g, '').slice(-4) : '4242';
      bankOrCard = `Tarjeta Crédito/Débito (•••• ${last4})`;
    } else if (form.method === 'BANCOLOMBIA_QR') {
      bankOrCard = 'Transferencia / QR Bancolombia';
    }

    // Deliberadamente sin montos: el neto lo decide el backend.
    // Mismo modo y misma selección que se simularon; sin montos.
    const checkoutPayload = { nit: group.nit, ...selection };

    return this.http.post<CheckoutResult>('/api/v1/pagos/checkout', checkoutPayload, { headers: this.auditHeaders() }).pipe(
      switchMap(checkout => {
        // La pasarela cobra exactamente el neto calculado por el servidor y lo confirma.
        const confirmPayload = {
          pagoId: checkout.pagoId,
          clientNit: group.nit,
          transactionId: randomCus,
          status: 'APROBADA',
          totalAmount: checkout.netoAPagar,
          paymentMethod: form.method,
          bankAccount: bankOrCard,
          authorizationCode: randomAuth,
          cus: randomCus,
          observation: `Pago confirmado desde Pasarela Virtual (${form.method})`
        };
        return this.http.post<{ paymentId: string; status: string; paidInvoiceIds: string[] }>('/api/v1/pagos/confirmar', confirmPayload).pipe(
          map(result => {
            if (!result?.paymentId || result.status !== 'APLICADO') {
              throw new Error(`El backend no aplicó el pago (estado ${result?.status ?? 'desconocido'})`);
            }
            this.markInvoicesAsPaid(selectedInvoices.map(f => f.clave_documento || `${f.tipo}:${f.consecutivo}`));
            // El siguiente pago es otro proceso: su historial de retenciones empieza de cero.
            this.selectionSession = GatewayService.newSelectionSession();
            const receipt: PaymentTransactionReceipt = {
              transactionId: randomCus,
              pagoId: checkout.pagoId,
              cus: randomCus,
              authorizationCode: randomAuth,
              paymentDate: new Date().toISOString(),
              paymentMethod: form.method,
              bankOrCardName: bankOrCard,
              clientNit: group.nit,
              clientBusinessName: group.razon_social || ('CLIENTE NIT ' + group.nit),
              invoicesPaid: selectedInvoices.map(f => ({
                claveDocumento: f.clave_documento || `${f.tipo}:${f.consecutivo}`,
                consecutivo: f.consecutivo,
                tipo: f.tipo,
                valor: f.valor_servicio,
                cufe: f.cufe
              })),
              totalAmount: checkout.netoAPagar,
              settlement: checkout.liquidacion,
              status: 'APROBADA',
              statusMessage: 'Transacción aprobada satisfactoriamente por la entidad bancaria.'
            };
            return receipt;
          })
        );
      }),
      // El pago solo se reporta como aprobado si el backend lo aplicó; cualquier error se propaga.
      catchError(err => {
        console.error('El backend no confirmó el pago; las facturas siguen pendientes.', err);
        return throwError(() => err);
      }),
      delay(1200)
    );
  }
}
