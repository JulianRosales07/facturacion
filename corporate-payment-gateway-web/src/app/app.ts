import { Component, DestroyRef, inject, signal, computed } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, debounceTime, forkJoin, map, of, switchMap, tap } from 'rxjs';
import { GatewayService } from './services/gateway.service';
import { RealtimeEvent, RealtimeService } from './services/realtime.service';
import {
  ClientInvoiceGroup, ClientRetentionConfig, ConceptSelectionPayload, GroupedInvoice, PaymentForm, PaymentMethodType, PaymentSettlement,
  PaymentTransactionReceipt, PseBank, RetentionApplicationMode, RetentionSelectionPayload, SettledInvoice,
  TaxCatalogConcept, WithholdingEvaluation
} from './models/gateway.model';

export type GatewayStep = 'SEARCH' | 'INVOICES' | 'PAYMENT_METHOD' | 'PROCESSING' | 'RECEIPT';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  private readonly gatewayService = inject(GatewayService);

  // Estado del flujo
  readonly currentStep = signal<GatewayStep>('SEARCH');
  readonly isLoading = signal<boolean>(false);
  readonly errorMessage = signal<string | null>(null);

  // Búsqueda por NIT
  readonly nitInput = signal<string>('900163149');
  readonly clientGroup = signal<ClientInvoiceGroup | null>(null);

  // Modal de QR
  readonly selectedQrInvoice = signal<GroupedInvoice | null>(null);
  readonly showQrModal = signal<boolean>(false);

  // Modal de JSON en vivo
  readonly showJsonViewer = signal<boolean>(false);

  // Métodos de pago y bancos
  readonly banks: PseBank[] = this.gatewayService.popularBanks;
  readonly selectedMethod = signal<PaymentMethodType>('PSE');

  // Formulario de pago
  paymentForm: PaymentForm = {
    method: 'PSE',
    payerName: 'CARLOS ALBERTO GÓMEZ',
    payerEmail: 'carlos.gomez@empresa.com',
    payerPhone: '3135963672',
    payerDocType: 'CC',
    payerDocNumber: '1088970617',
    bankCode: '1007',
    userType: 'JURIDICA',
    cardNumber: '4532 8901 2345 6789',
    cardHolder: 'CARLOS ALBERTO GÓMEZ',
    cardExpiry: '11/29',
    cardCvv: '884',
    installments: 1
  };

  // Transacción completada
  readonly receipt = signal<PaymentTransactionReceipt | null>(null);

  // Retenciones y deducibles fiscales (HU-51): el catálogo define qué conceptos existen y su
  // porcentaje máximo; el cliente marca los que practica y escribe el porcentaje de cada uno.
  // La liquidación la calcula siempre el backend.
  readonly taxCatalog = signal<TaxCatalogConcept[]>([]);
  readonly taxCatalogError = signal<string | null>(null);
  // Modo de aplicación (CA-11): lo elige el cliente; sin regla de negocio definida no hay valor por defecto.
  readonly applicationMode = signal<RetentionApplicationMode | null>(null);
  readonly modeNotice = signal<string | null>(null);
  // CONSOLIDATED: concepto → porcentaje ingresado (null mientras el cliente no lo escribe).
  readonly consolidatedPercentages = signal<Map<string, number | null>>(new Map());
  // PER_INVOICE: factura → (concepto → porcentaje) y facturas con su panel desplegado.
  readonly perInvoiceConcepts = signal<Map<string, Map<string, number | null>>>(new Map());
  readonly expandedInvoices = signal<Set<string>>(new Set());
  readonly settlement = signal<PaymentSettlement | null>(null);
  readonly simulating = signal<boolean>(false);
  readonly simulationError = signal<string | null>(null);
  private readonly simulationRequests = new Subject<void>();
  private readonly destroyRef = inject(DestroyRef);

  // Configuración propia del cliente: solo existe si él autorizó guardarla.
  readonly savedConfig = signal<ClientRetentionConfig | null>(null);
  readonly savedConfigNotice = signal<string | null>(null);
  readonly showConsentModal = signal<boolean>(false);
  readonly savingConsent = signal<boolean>(false);

  // Tiempo real (WebSocket): el aviso llega por el canal del NIT y el estado real se vuelve a
  // consultar por REST. Así ningún cálculo monetario depende del contenido del evento.
  private readonly realtime = inject(RealtimeService);
  readonly realtimeState = this.realtime.state;
  readonly realtimeNotice = signal<string | null>(null);
  private unwatchNit: (() => void) | null = null;
  private watchedNit: string | null = null;
  private readonly refreshRequests = new Subject<void>();
  private readonly lastKnownStatus = new Map<string, string>();

  constructor() {
    this.refreshRequests.pipe(
      debounceTime(300),
      switchMap(() => {
        const nit = this.watchedNit;
        if (!nit) return of(null);
        return this.gatewayService.getInvoicesByNit(nit).pipe(
          map(group => ({ group, error: null as unknown })),
          catchError(error => of({ group: null, error }))
        );
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(result => { if (result) this.applyRefresh(result.group, result.error); });

    // Tras reconectar no se sabe qué cambió mientras no hubo conexión: se resincroniza por REST.
    this.realtime.reconnected$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      if (this.watchedNit && this.isEditingPayment()) this.refreshRequests.next();
    });

    // switchMap descarta respuestas de selecciones anteriores: el resumen siempre corresponde a
    // la última combinación de facturas, conceptos y porcentajes.
    this.simulationRequests.pipe(
      tap(() => { this.simulating.set(true); this.simulationError.set(null); }),
      debounceTime(250),
      switchMap(() => {
        const group = this.clientGroup();
        const selection = this.retentionSelection();
        if (!group || !selection) {
          return of(null);
        }
        return this.gatewayService.simulate(group.nit, selection).pipe(
          catchError(err => {
            this.simulationError.set(err?.error?.mensaje || 'No fue posible calcular la liquidación. Intenta de nuevo.');
            return of(null);
          })
        );
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(result => {
      this.settlement.set(result);
      this.simulating.set(false);
    });
  }

  /** Neto a pagar calculado por el backend; sin liquidación vigente no se permite continuar. */
  readonly netPayable = computed(() => this.settlement()?.netoAPagar ?? null);

  /** Mensaje cuando hay retenciones marcadas sin un porcentaje válido; bloquea la simulación. */
  readonly percentageIssue = computed(() => {
    const maps = this.applicationMode() === 'PER_INVOICE'
      ? this.selectedInvoices().map(f => this.perInvoiceConcepts().get(f.id) ?? new Map<string, number | null>())
      : [this.consolidatedPercentages()];
    for (const map of maps) {
      for (const [conceptId, value] of map) {
        if (this.percentageError(conceptId, value)) {
          return 'Ingresa un porcentaje válido para cada retención marcada.';
        }
      }
    }
    return null;
  });

  readonly canProceed = computed(() =>
    this.selectedInvoices().length > 0 && !!this.applicationMode() && !!this.settlement()
      && !this.simulating() && !this.simulationError() && !this.percentageIssue());

  conceptById(conceptId: string): TaxCatalogConcept | undefined {
    return this.taxCatalog().find(c => c.id === conceptId);
  }

  /** Ayuda inmediata al escribir; el backend vuelve a validar contra el máximo vigente. */
  percentageError(conceptId: string, value: number | null | undefined): string | null {
    if (value === null || value === undefined || Number.isNaN(value)) return 'Ingresa el porcentaje';
    if (value <= 0) return 'Debe ser mayor que 0';
    const max = this.conceptById(conceptId)?.porcentajeMaximo;
    if (max !== undefined && value > max) return `Máximo ${max}%`;
    if (!/^\d+(\.\d{1,4})?$/.test(String(value))) return 'Máximo 4 decimales';
    return null;
  }

  private static toPayload(map: Map<string, number | null> | undefined): ConceptSelectionPayload[] {
    return Array.from(map ?? new Map<string, number | null>())
      .map(([conceptId, percentage]) => ({ conceptId, percentage: percentage as number }));
  }

  /**
   * Payload de retenciones según el modo elegido, con el porcentaje de cada concepto. Es el mismo
   * para simular y para el checkout (CA-16). Sin modo, o con porcentajes pendientes, no se simula.
   */
  retentionSelection(): RetentionSelectionPayload | null {
    const mode = this.applicationMode();
    const invoices = this.selectedInvoices();
    if (!mode || invoices.length === 0 || this.percentageIssue()) return null;
    if (mode === 'CONSOLIDATED') {
      return {
        applicationMode: mode,
        invoiceIds: invoices.map(f => f.id),
        selectedConcepts: App.toPayload(this.consolidatedPercentages())
      };
    }
    const byInvoice = this.perInvoiceConcepts();
    return {
      applicationMode: mode,
      invoices: invoices.map(f => ({ invoiceId: f.id, selectedConcepts: App.toPayload(byInvoice.get(f.id)) }))
    };
  }

  private hasRetentionSelections(): boolean {
    return this.consolidatedPercentages().size > 0
      || Array.from(this.perInvoiceConcepts().values()).some(concepts => concepts.size > 0);
  }

  /**
   * Cambiar de modo nunca reutiliza la selección anterior: se limpian los conceptos (se
   * conservan las facturas), se avisa al cliente y se vuelve a simular.
   */
  setApplicationMode(mode: RetentionApplicationMode): void {
    const previous = this.applicationMode();
    if (previous === mode) return;
    const hadSelections = this.hasRetentionSelections();
    this.consolidatedPercentages.set(new Map());
    this.perInvoiceConcepts.set(new Map());
    this.expandedInvoices.set(new Set(mode === 'PER_INVOICE' ? this.selectedInvoices().map(f => f.id) : []));
    this.applicationMode.set(mode);
    this.savedConfigNotice.set(null);
    this.modeNotice.set(previous && hadSelections
      ? 'Cambiaste la forma de aplicar las retenciones: se limpió la selección anterior. Vuelve a marcar los conceptos y sus porcentajes.'
      : null);
    this.requestSimulation();
  }

  private resetRetentionConfiguration(): void {
    this.applicationMode.set(null);
    this.modeNotice.set(null);
    this.consolidatedPercentages.set(new Map());
    this.perInvoiceConcepts.set(new Map());
    this.expandedInvoices.set(new Set());
    this.savedConfig.set(null);
    this.savedConfigNotice.set(null);
  }

  isInvoiceExpanded(invoiceId: string): boolean {
    return this.expandedInvoices().has(invoiceId);
  }

  toggleInvoiceExpanded(invoiceId: string): void {
    const next = new Set(this.expandedInvoices());
    if (next.has(invoiceId)) next.delete(invoiceId); else next.add(invoiceId);
    this.expandedInvoices.set(next);
  }

  isInvoiceConceptSelected(invoiceId: string, conceptId: string): boolean {
    return this.perInvoiceConcepts().get(invoiceId)?.has(conceptId) ?? false;
  }

  invoicePercentage(invoiceId: string, conceptId: string): number | null {
    return this.perInvoiceConcepts().get(invoiceId)?.get(conceptId) ?? null;
  }

  toggleInvoiceConcept(invoiceId: string, conceptId: string): void {
    const next = new Map(this.perInvoiceConcepts());
    const concepts = new Map(next.get(invoiceId) ?? []);
    if (concepts.has(conceptId)) concepts.delete(conceptId); else concepts.set(conceptId, null);
    next.set(invoiceId, concepts);
    this.perInvoiceConcepts.set(next);
    this.modeNotice.set(null);
    this.requestSimulation();
  }

  setInvoicePercentage(invoiceId: string, conceptId: string, value: number | null): void {
    const next = new Map(this.perInvoiceConcepts());
    const concepts = new Map(next.get(invoiceId) ?? []);
    concepts.set(conceptId, value);
    next.set(invoiceId, concepts);
    this.perInvoiceConcepts.set(next);
    this.requestSimulation();
  }

  /** Liquidación de una factura (PER_INVOICE): sus retenciones y su neto, calculados por el backend. */
  settledInvoice(invoiceId: string): SettledInvoice | undefined {
    return this.settlement()?.facturas.find(f => f.id === invoiceId);
  }

  invoiceEvaluationFor(invoiceId: string, conceptId: string): WithholdingEvaluation | undefined {
    return this.settledInvoice(invoiceId)?.retenciones.find(r => r.conceptoId === conceptId);
  }

  invoiceLabel(settlement: PaymentSettlement, invoiceId: string): string {
    const invoice = settlement.facturas.find(f => f.id === invoiceId);
    return invoice ? `${invoice.tipo}-${invoice.numero}` : '';
  }

  selectedConceptCount(invoiceId: string): number {
    return this.perInvoiceConcepts().get(invoiceId)?.size ?? 0;
  }

  private requestSimulation(): void {
    this.settlement.set(null);
    this.simulationRequests.next();
  }

  /** Carga catálogo y configuración guardada juntos: la precarga necesita el catálogo vigente. */
  private loadRetentionSetup(nit: string): void {
    this.taxCatalogError.set(null);
    forkJoin({
      catalog: this.gatewayService.getTaxCatalog(),
      config: this.gatewayService.getRetentionConfig(nit).pipe(catchError(() => of(null)))
    }).subscribe({
      next: ({ catalog, config }) => {
        this.taxCatalog.set(catalog);
        this.savedConfig.set(config);
        if (config) this.applySavedConfig(config);
        this.requestSimulation();
      },
      error: () => {
        this.taxCatalog.set([]);
        this.taxCatalogError.set('No fue posible cargar el catálogo de retenciones.');
      }
    });
  }

  /** Precarga lo que el cliente autorizó; solo conceptos que siguen vigentes y dentro del máximo. */
  private applySavedConfig(config: ClientRetentionConfig): void {
    const catalogIds = new Set(this.taxCatalog().map(c => c.id));
    const usable = config.conceptos.filter(c => c.utilizable && catalogIds.has(c.conceptId));
    const skipped = config.conceptos.length - usable.length;
    const percentages = new Map<string, number | null>(usable.map(c => [c.conceptId, c.percentage]));

    this.applicationMode.set(config.applicationMode);
    if (config.applicationMode === 'CONSOLIDATED') {
      this.consolidatedPercentages.set(percentages);
    } else {
      const byInvoice = new Map<string, Map<string, number | null>>();
      this.selectedInvoices().forEach(f => byInvoice.set(f.id, new Map(percentages)));
      this.perInvoiceConcepts.set(byInvoice);
      this.expandedInvoices.set(new Set(this.selectedInvoices().map(f => f.id)));
    }
    const date = new Date(config.autorizadoEn).toLocaleDateString('es-CO');
    this.savedConfigNotice.set(`Se precargó la configuración de retenciones que autorizaste el ${date}. Puedes modificarla antes de pagar.`
      + (skipped > 0 ? ` ${skipped} retención(es) guardada(s) ya no están vigentes o superan el máximo actual; ingrésalas de nuevo si aplican.` : ''));
  }

  forgetSavedConfig(): void {
    const group = this.clientGroup();
    if (!group || !confirm('¿Eliminar la configuración de retenciones guardada? En tus próximos pagos tendrás que ingresar los porcentajes de nuevo.')) return;
    this.gatewayService.deleteRetentionConfig(group.nit).subscribe({
      next: () => {
        this.savedConfig.set(null);
        this.savedConfigNotice.set('Se eliminó la configuración guardada. Este pago conserva lo que tienes marcado.');
      },
      error: () => alert('No fue posible eliminar la configuración guardada. Intenta de nuevo.')
    });
  }

  isConceptSelected(conceptId: string): boolean {
    return this.consolidatedPercentages().has(conceptId);
  }

  consolidatedPercentage(conceptId: string): number | null {
    return this.consolidatedPercentages().get(conceptId) ?? null;
  }

  toggleConcept(conceptId: string): void {
    const next = new Map(this.consolidatedPercentages());
    if (next.has(conceptId)) {
      next.delete(conceptId);
    } else {
      next.set(conceptId, null);
    }
    this.consolidatedPercentages.set(next);
    this.modeNotice.set(null);
    this.requestSimulation();
  }

  setConsolidatedPercentage(conceptId: string, value: number | null): void {
    const next = new Map(this.consolidatedPercentages());
    next.set(conceptId, value);
    this.consolidatedPercentages.set(next);
    this.requestSimulation();
  }

  /** Evaluación del backend para un concepto marcado (aplica / no aplica y su motivo). */
  evaluationFor(conceptId: string): WithholdingEvaluation | undefined {
    return this.settlement()?.retenciones.find(r => r.conceptoId === conceptId && !r.facturaId);
  }

  /**
   * Configuración que se puede guardar como propia del cliente. En PER_INVOICE solo si todas las
   * facturas llevan exactamente los mismos conceptos y porcentajes (se reaplicará a cada factura).
   */
  private savableConfiguration(): { mode: RetentionApplicationMode; concepts: ConceptSelectionPayload[] } | null {
    const mode = this.applicationMode();
    if (!mode) return null;
    if (mode === 'CONSOLIDATED') {
      const concepts = App.toPayload(this.consolidatedPercentages());
      return concepts.length ? { mode, concepts } : null;
    }
    const sets = this.selectedInvoices().map(f => App.toPayload(this.perInvoiceConcepts().get(f.id)));
    const key = (c: ConceptSelectionPayload[]) => c.map(x => `${x.conceptId}@${x.percentage}`).sort().join('|');
    if (!sets.length || !sets[0].length || sets.some(s => key(s) !== key(sets[0]))) return null;
    return { mode, concepts: sets[0] };
  }

  private sameAsSaved(candidate: { mode: RetentionApplicationMode; concepts: ConceptSelectionPayload[] }): boolean {
    const saved = this.savedConfig();
    if (!saved || saved.applicationMode !== candidate.mode) return false;
    const key = (c: { conceptId: string; percentage: number }[]) => c.map(x => `${x.conceptId}@${Number(x.percentage)}`).sort().join('|');
    return key(saved.conceptos.filter(c => c.utilizable)) === key(candidate.concepts);
  }

  /** El cliente acepta: se guarda su configuración y continúa al pago. */
  acceptConsent(): void {
    const group = this.clientGroup();
    const candidate = this.savableConfiguration();
    if (!group || !candidate) { this.declineConsent(); return; }
    this.savingConsent.set(true);
    this.gatewayService.saveRetentionConfig(group.nit, candidate.mode, candidate.concepts).subscribe({
      next: saved => {
        this.savingConsent.set(false);
        this.savedConfig.set(saved);
        this.showConsentModal.set(false);
        this.currentStep.set('PAYMENT_METHOD');
      },
      error: err => {
        this.savingConsent.set(false);
        alert(err?.error?.mensaje || 'No fue posible guardar la configuración. Puedes continuar sin guardarla.');
      }
    });
  }

  /** El cliente no autoriza: no se guarda nada y en sus próximos pagos deberá ingresar los porcentajes. */
  declineConsent(): void {
    this.showConsentModal.set(false);
    this.currentStep.set('PAYMENT_METHOD');
  }

  // Cálculos reactivos con Signals
  readonly selectedInvoices = computed(() => {
    const group = this.clientGroup();
    if (!group || !group.facturas) return [];
    return group.facturas.filter(f => f.seleccionada);
  });

  readonly totalSelectedAmount = computed(() => {
    return this.selectedInvoices().reduce((acc, f) => acc + (f.valor_servicio || 0), 0);
  });

  readonly allSelected = computed(() => {
    const group = this.clientGroup();
    if (!group || !group.facturas || group.facturas.length === 0) return false;
    return group.facturas.every(f => f.seleccionada);
  });

  // Texto amigable para tarjeta
  readonly formattedCardNumber = computed(() => {
    const num = this.paymentForm.cardNumber || '•••• •••• •••• ••••';
    return num;
  });

  /**
   * Consulta facturas por NIT consumiendo el JSON oficial.
   */
  searchInvoices(): void {
    const nit = this.nitInput().trim();
    if (!nit) {
      this.errorMessage.set('Por favor ingresa un número de NIT válido.');
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set(null);

    this.gatewayService.getInvoicesByNit(nit).subscribe({
      next: (group) => {
        this.clientGroup.set(group);
        this.isLoading.set(false);
        this.resetRetentionConfiguration();
        this.currentStep.set('INVOICES');
        this.loadRetentionSetup(group.nit);
        this.watchNit(group.nit);
      },
      error: (err) => {
        this.isLoading.set(false);
        if (err?.message === 'NO_PENDING_INVOICES') {
          this.errorMessage.set(`¡Empresa al día! El NIT ${nit} no tiene facturas pendientes de cobro (todas las facturas fueron pagadas exitosamente).`);
        } else {
          this.errorMessage.set(`No se encontraron facturas pendientes para el NIT ${nit} o hubo un error de conexión con el servicio.`);
        }
      }
    });
  }

  /**
   * Permite restablecer los pagos simulados para volver a probar el flujo de pagos.
   */
  resetPaidInvoicesSimulation(): void {
    this.gatewayService.clearPaidKeys();
    alert('Se ha restablecido el estado de facturas simuladas. Ahora volverán a aparecer como pendientes.');
    this.resetFlow();
  }

  /**
   * Accesos directos a NITs de prueba.
   */
  selectQuickNit(nit: string): void {
    this.nitInput.set(nit);
    this.searchInvoices();
  }

  /**
   * Alternar selección de una factura específica.
   */
  toggleInvoiceSelection(invoice: GroupedInvoice): void {
    invoice.seleccionada = !invoice.seleccionada;
    if (invoice.seleccionada && this.applicationMode() === 'PER_INVOICE') {
      this.expandedInvoices.set(new Set([...this.expandedInvoices(), invoice.id]));
    }
    // Forzar actualización del signal de grupo
    const group = this.clientGroup();
    if (group) {
      this.clientGroup.set({ ...group });
    }
    this.requestSimulation();
  }

  /**
   * Seleccionar o deseleccionar todas las facturas.
   */
  toggleSelectAll(): void {
    const currentAll = this.allSelected();
    const group = this.clientGroup();
    if (group && group.facturas) {
      group.facturas.forEach(f => f.seleccionada = !currentAll);
      this.clientGroup.set({ ...group });
    }
    this.requestSimulation();
  }

  /**
   * Pasar al paso de selección de medio de pago.
   */
  proceedToPayment(): void {
    if (this.selectedInvoices().length === 0) {
      alert('Debes seleccionar al menos una factura para pagar.');
      return;
    }
    if (!this.canProceed()) {
      return;
    }
    // Si el cliente configuró retenciones distintas a las guardadas, se le pregunta si autoriza
    // usarlas en sus próximas facturas. Sin su aceptación no se guarda nada.
    const candidate = this.savableConfiguration();
    if (candidate && !this.sameAsSaved(candidate)) {
      this.showConsentModal.set(true);
      return;
    }
    this.currentStep.set('PAYMENT_METHOD');
  }

  /**
   * Seleccionar método de pago.
   */
  setPaymentMethod(method: PaymentMethodType): void {
    this.selectedMethod.set(method);
    this.paymentForm.method = method;
  }

  /**
   * Procesar el pago simulado.
   */
  submitPayment(): void {
    const group = this.clientGroup();
    const selected = this.selectedInvoices();
    const selection = this.retentionSelection();
    if (!group || selected.length === 0 || !selection) return;

    this.currentStep.set('PROCESSING');

    this.gatewayService.processPayment(group, selected, selection, this.paymentForm).subscribe({
      next: (receipt) => {
        this.receipt.set(receipt);
        this.currentStep.set('RECEIPT');
      },
      error: (err) => {
        const detail = err?.error?.mensaje || err?.message || 'sin respuesta del servidor';
        alert(`El pago no pudo ser confirmado y las facturas siguen pendientes (${detail}). Por favor intenta de nuevo.`);
        this.currentStep.set('PAYMENT_METHOD');
      }
    });
  }

  /**
   * Ver detalle de QR de una factura.
   */
  // Visor del PDF oficial de una factura: modal sobre el paso de facturas. Abrirlo o cerrarlo no
  // toca la selección de facturas, el modo ni las retenciones, ni vuelve a simular.
  private readonly sanitizer = inject(DomSanitizer);
  readonly pdfInvoice = signal<GroupedInvoice | null>(null);
  readonly pdfViewerUrl = signal<SafeResourceUrl | null>(null);
  readonly pdfLoading = signal<boolean>(false);
  readonly pdfError = signal<string | null>(null);
  readonly pdfDownloading = signal<string | null>(null);
  private pdfObjectUrl: string | null = null;

  openPdfViewer(invoice: GroupedInvoice): void {
    if (!invoice.pdf_disponible) return;
    this.releasePdfUrl();
    this.pdfInvoice.set(invoice);
    this.pdfError.set(null);
    this.pdfLoading.set(true);
    this.gatewayService.getInvoicePdf(invoice.id, 'inline').subscribe({
      next: blob => {
        // Se descarta la respuesta si el usuario ya cerró o abrió otra factura.
        if (this.pdfInvoice()?.id !== invoice.id) return;
        this.pdfObjectUrl = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }));
        this.pdfViewerUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(this.pdfObjectUrl));
        this.pdfLoading.set(false);
      },
      error: err => {
        if (this.pdfInvoice()?.id !== invoice.id) return;
        this.pdfLoading.set(false);
        this.pdfError.set(err?.status === 404
          ? 'El PDF de esta factura aún no está disponible.'
          : 'No fue posible abrir el PDF de la factura. Intenta de nuevo o descárgalo.');
      }
    });
  }

  closePdfViewer(): void {
    this.releasePdfUrl();
    this.pdfInvoice.set(null);
    this.pdfError.set(null);
    this.pdfLoading.set(false);
  }

  private releasePdfUrl(): void {
    if (this.pdfObjectUrl) URL.revokeObjectURL(this.pdfObjectUrl);
    this.pdfObjectUrl = null;
    this.pdfViewerUrl.set(null);
  }

  /** Descarga el PDF original de esa factura con nombre F1-51980.pdf. */
  downloadInvoicePdf(invoice: GroupedInvoice): void {
    if (!invoice.pdf_disponible || this.pdfDownloading()) return;
    this.pdfDownloading.set(invoice.id);
    this.gatewayService.getInvoicePdf(invoice.id, 'attachment').subscribe({
      next: blob => {
        const url = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }));
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = GatewayService.pdfFilename(invoice);
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        this.pdfDownloading.set(null);
      },
      error: err => {
        this.pdfDownloading.set(null);
        alert(err?.status === 404 ? 'El PDF de esta factura aún no está disponible.' : 'No fue posible descargar el PDF. Intenta de nuevo.');
      }
    });
  }

  openQrModal(invoice: GroupedInvoice): void {
    this.selectedQrInvoice.set(invoice);
    this.showQrModal.set(true);
  }

  closeQrModal(): void {
    this.showQrModal.set(false);
    this.selectedQrInvoice.set(null);
  }

  /**
   * Visor de JSON.
   */
  toggleJsonViewer(): void {
    this.showJsonViewer.set(!this.showJsonViewer());
  }

  /**
   * Imprimir o guardar comprobante.
   */
  printReceipt(): void {
    window.print();
  }

  /**
   * Reiniciar flujo para consultar otro NIT.
   */
  /** Una sola suscripción activa: al cambiar de NIT se abandona el canal anterior. */
  private watchNit(nit: string): void {
    if (this.watchedNit === nit && this.unwatchNit) return;
    this.unwatchNit?.();
    this.watchedNit = nit;
    this.realtimeNotice.set(null);
    this.unwatchNit = this.realtime.subscribe(`/topic/invoices/${nit}`, event => this.onRealtimeEvent(event));
  }

  private stopWatching(): void {
    this.unwatchNit?.();
    this.unwatchNit = null;
    this.watchedNit = null;
  }

  /** Solo mientras el cliente arma su pago; en PROCESANDO o COMPROBANTE los cambios son de su propio pago. */
  private isEditingPayment(): boolean {
    return this.currentStep() === 'INVOICES' || this.currentStep() === 'PAYMENT_METHOD';
  }

  onRealtimeEvent(event: RealtimeEvent): void {
    const payload = event.payload ?? {};
    if (event.eventType === 'INVOICE_STATUS_CHANGED' && typeof payload['invoiceId'] === 'string') {
      this.lastKnownStatus.set(payload['invoiceId'] as string, String(payload['newStatus'] ?? ''));
    }
    if (event.eventType === 'PAYMENT_STATUS_CHANGED' && this.currentStep() === 'PROCESSING' && payload['newStatus'] === 'APLICADO') {
      this.realtimeNotice.set('Pago aprobado por la entidad financiera. Generando tu comprobante…');
      return;
    }
    if (!this.isEditingPayment()) return;
    if (event.eventType === 'INVOICE_STATUS_CHANGED' || event.eventType === 'INVOICE_PDF_AVAILABLE'
        || (event.eventType === 'PAYMENT_STATUS_CHANGED' && payload['newStatus'] === 'APLICADO')) {
      this.refreshRequests.next();
    }
  }

  /**
   * Sincroniza con el estado real conservando la selección del cliente. Las facturas que ya no
   * están pendientes (pagadas o anuladas en otra sesión) salen de la operación y la liquidación se
   * vuelve a pedir al backend; nunca se recalcula localmente.
   */
  applyRefresh(fresh: ClientInvoiceGroup | null, error: unknown): void {
    const current = this.clientGroup();
    if (!current) return;
    const allPaid = !fresh && (error as { message?: string } | null)?.message === 'NO_PENDING_INVOICES';
    if (!fresh && !allPaid) return; // falla de red: se conserva la vista y se reintenta en el próximo aviso o reconexión

    const freshInvoices = fresh?.facturas ?? [];
    const selectedIds = new Set(current.facturas.filter(f => f.seleccionada).map(f => f.id));
    const freshIds = new Set(freshInvoices.map(f => f.id));
    const removedSelected = current.facturas.filter(f => selectedIds.has(f.id) && !freshIds.has(f.id));
    const merged = freshInvoices.map(f => ({ ...f, seleccionada: selectedIds.has(f.id) }));

    this.clientGroup.set({
      ...current,
      ...(fresh ?? {}),
      facturas: merged,
      cantidad_facturas: merged.length,
      valor_total_servicio: merged.reduce((sum, f) => sum + (f.valor_servicio || 0), 0)
    });

    const removedIds = current.facturas.filter(f => !freshIds.has(f.id)).map(f => f.id);
    if (removedIds.length) {
      const concepts = new Map(this.perInvoiceConcepts());
      const expanded = new Set(this.expandedInvoices());
      removedIds.forEach(id => { concepts.delete(id); expanded.delete(id); });
      this.perInvoiceConcepts.set(concepts);
      this.expandedInvoices.set(expanded);
    }

    if (removedSelected.length) {
      const describe = (f: GroupedInvoice) => `${f.tipo}-${f.consecutivo}`;
      const verb = (f: GroupedInvoice) => this.lastKnownStatus.get(f.id) === 'ANULADA' ? 'anulada' : 'pagada';
      this.realtimeNotice.set(removedSelected.length === 1
        ? `La factura ${describe(removedSelected[0])} acaba de ser ${verb(removedSelected[0])} y fue retirada de esta operación.`
        : `Las facturas ${removedSelected.map(describe).join(', ')} acaban de ser pagadas o anuladas y fueron retiradas de esta operación.`);
      if (this.currentStep() === 'PAYMENT_METHOD') this.currentStep.set('INVOICES');
    }
    if (merged.length === 0) {
      this.realtimeNotice.set('Todas las facturas de este NIT ya fueron pagadas. No hay saldo pendiente por pagar.');
    }
    this.requestSimulation();
  }

  dismissRealtimeNotice(): void {
    this.realtimeNotice.set(null);
  }

  resetFlow(): void {
    this.stopWatching();
    this.realtimeNotice.set(null);
    this.clientGroup.set(null);
    this.receipt.set(null);
    this.settlement.set(null);
    this.resetRetentionConfiguration();
    this.currentStep.set('SEARCH');
    this.errorMessage.set(null);
  }

  getJsonString(): string {
    return JSON.stringify(this.clientGroup(), null, 2);
  }
}
