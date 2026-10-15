import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';
import { GatewayService } from './services/gateway.service';
import { STOMP_CLIENT_FACTORY } from './services/realtime.service';
import { ClientInvoiceGroup, GroupedInvoice } from './models/gateway.model';

function invoice(consecutivo: string, pdf: boolean): GroupedInvoice {
  return {
    id: `id-${consecutivo}`,
    tipo: 'F1',
    consecutivo,
    clave_documento: `F1:${consecutivo}`,
    valor_servicio: 101150,
    fecha_emision: '2026-09-08',
    fecha_vencimiento: '2026-09-23',
    pdf_disponible: pdf,
    cufe: null,
    seleccionada: true
  };
}

describe('App — PDF oficial por factura', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    // jsdom no implementa object URLs.
    (URL as unknown as { createObjectURL: () => string }).createObjectURL = () => 'blob:pdf-test';
    (URL as unknown as { revokeObjectURL: () => void }).revokeObjectURL = () => undefined;
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function appWith(facturas: GroupedInvoice[]) {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    const group: ClientInvoiceGroup = {
      nit: '1085285659', razon_social: 'CLIENTE', cantidad_facturas: facturas.length, facturas, valor_total_servicio: 0
    };
    app.clientGroup.set(group);
    app.currentStep.set('INVOICES');
    return { fixture, app };
  }

  it('Caso 1: abre el PDF de la factura en el visor (inline, por su id) sin cambiar de paso', () => {
    const { app } = appWith([invoice('51980', true)]);
    app.openPdfViewer(app.clientGroup()!.facturas[0]);

    const req = http.expectOne(r => r.url === '/api/v1/facturas/id-51980/pdf');
    expect(req.request.params.get('disposition')).toBe('inline');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['%PDF-1.7'], { type: 'application/pdf' }));

    expect(app.pdfViewerUrl()).not.toBeNull();
    expect(app.pdfInvoice()?.consecutivo).toBe('51980');
    expect(app.currentStep()).toBe('INVOICES');
  });

  it('Caso 2: sin PDF las acciones quedan deshabilitadas y no se consulta el backend', async () => {
    const { fixture, app } = appWith([invoice('51981', false)]);
    fixture.detectChanges();
    await fixture.whenStable();

    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.inv-pdf-actions button'));
    expect(buttons.length).toBe(2);
    expect(buttons.every(b => b.disabled)).toBe(true);
    expect((fixture.nativeElement as HTMLElement).querySelector('.pdf-status')?.textContent).toContain('Pendiente');

    app.openPdfViewer(app.clientGroup()!.facturas[0]);
    app.downloadInvoicePdf(app.clientGroup()!.facturas[0]);
    http.expectNone(() => true);
  });

  it('Caso 3: con varias facturas del NIT, cada botón pide exclusivamente su propio PDF', () => {
    const { app } = appWith([invoice('51980', true), invoice('51981', true), invoice('51982', true)]);
    for (const factura of app.clientGroup()!.facturas) {
      app.openPdfViewer(factura);
      http.expectOne(r => r.url === `/api/v1/facturas/${factura.id}/pdf`).flush(new Blob(['%PDF-1.7']));
      expect(app.pdfInvoice()?.id).toBe(factura.id);
    }
  });

  it('Caso 4: abrir y cerrar el visor conserva facturas, modo, retenciones y simulación', () => {
    const { app } = appWith([invoice('51980', true), invoice('51981', true)]);
    app.applicationMode.set('CONSOLIDATED');
    app.consolidatedPercentages.set(new Map([['retefuente', 4]]));
    const settlement = { netoAPagar: 1 } as never;
    app.settlement.set(settlement);
    const selectedBefore = app.selectedInvoices().map(f => f.id);

    app.openPdfViewer(app.clientGroup()!.facturas[0]);
    http.expectOne(r => r.url.endsWith('/id-51980/pdf')).flush(new Blob(['%PDF-1.7']));
    app.closePdfViewer();

    expect(app.pdfInvoice()).toBeNull();
    expect(app.pdfViewerUrl()).toBeNull();
    expect(app.selectedInvoices().map(f => f.id)).toEqual(selectedBefore);
    expect(app.applicationMode()).toBe('CONSOLIDATED');
    expect(app.consolidatedPercentages().get('retefuente')).toBe(4);
    expect(app.settlement()).toBe(settlement);
    http.expectNone(r => r.url.includes('/pagos/simular'));
  });

  it('descarga con el nombre de la factura y no con un nombre genérico', () => {
    expect(GatewayService.pdfFilename({ tipo: 'F1', consecutivo: '51980' })).toBe('F1-51980.pdf');
    expect(GatewayService.pdfFilename({ tipo: 'F1', consecutivo: 'FESP 50776' })).toBe('F1-FESP_50776.pdf');

    const { app } = appWith([invoice('51980', true)]);
    app.downloadInvoicePdf(app.clientGroup()!.facturas[0]);
    const req = http.expectOne(r => r.url === '/api/v1/facturas/id-51980/pdf');
    expect(req.request.params.get('disposition')).toBe('attachment');
    req.flush(new Blob(['%PDF-1.7']));
  });
});

describe('App — tiempo real (WebSocket)', () => {
  let http: HttpTestingController;
  const stompSubscriptions: string[] = [];

  beforeEach(async () => {
    stompSubscriptions.length = 0;
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        // Cliente STOMP simulado: registra suscripciones sin abrir un WebSocket real.
        { provide: STOMP_CLIENT_FACTORY, useValue: (config: { onConnect?: (frame: never) => void }) => ({
          active: false, connected: false, reconnectDelay: 1000, connectHeaders: {},
          activate() { this.active = true; this.connected = true; config.onConnect?.({} as never); }, deactivate() { this.active = false; },
          subscribe(destination: string) { stompSubscriptions.push(destination); return { id: destination, unsubscribe: () => undefined }; }
        }) }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

  function appWithTwoInvoices() {
    const app = TestBed.createComponent(App).componentInstance;
    const a = { ...invoice('51987', true), valor_servicio: 5_500_000 };
    const b = { ...invoice('51991', true), valor_servicio: 9_500_000 };
    app.clientGroup.set({ nit: '800092198', razon_social: 'CLIENTE', cantidad_facturas: 2, facturas: [a, b], valor_total_servicio: 15_000_000 });
    app.currentStep.set('INVOICES');
    return { app, a, b };
  }

  it('Caso 2 (CONSOLIDATED): la factura pagada en otra sesión se retira, se avisa y se vuelve a simular con las restantes', async () => {
    const { app, a, b } = appWithTwoInvoices();
    app.applicationMode.set('CONSOLIDATED');
    app.consolidatedPercentages.set(new Map([['retefuente', 4]]));

    app['watchNit']('800092198');
    expect(stompSubscriptions).toEqual(['/topic/invoices/800092198']);

    app.onRealtimeEvent({ eventId: 'e1', eventType: 'INVOICE_STATUS_CHANGED', occurredAt: '',
      payload: { invoiceId: a.id, invoiceNumber: 'F1-51987', newStatus: 'PAGADA' } });
    await wait(350);
    // El aviso no se usa para calcular: se consulta el estado real por REST.
    http.expectOne(r => r.url.includes('/api/v1/facturas/recibidas/por-nit')).flush({
      items: [{ nit: '800092198', razon_social: 'CLIENTE', cantidad_facturas: 1, facturas: [b], valor_total_servicio: 9_500_000 }]
    });

    expect(app.selectedInvoices().map(f => f.consecutivo)).toEqual(['51991']);
    expect(app.clientGroup()!.cantidad_facturas).toBe(1);
    expect(app.realtimeNotice()).toContain('La factura F1-51987 acaba de ser pagada y fue retirada de esta operación');
    expect(app.consolidatedPercentages().get('retefuente')).toBe(4);

    await wait(300);
    const simulation = http.expectOne('/api/v1/pagos/simular');
    expect(simulation.request.body.invoiceIds).toEqual([b.id]);
    expect(simulation.request.body.selectedConcepts).toEqual([{ conceptId: 'retefuente', percentage: 4 }]);
    simulation.flush({ netoAPagar: 9_120_000, retenciones: [], facturas: [] });
  });

  it('Caso 2 (PER_INVOICE): también se eliminan las retenciones de la factura retirada', async () => {
    const { app, a, b } = appWithTwoInvoices();
    app.applicationMode.set('PER_INVOICE');
    app.perInvoiceConcepts.set(new Map([[a.id, new Map([['reteica', 0.6]])], [b.id, new Map([['reteiva', 15]])]]));

    app.applyRefresh({ nit: '800092198', razon_social: 'CLIENTE', cantidad_facturas: 1, facturas: [b], valor_total_servicio: 9_500_000 }, null);

    expect(app.perInvoiceConcepts().has(a.id)).toBe(false);
    expect(app.perInvoiceConcepts().get(b.id)?.get('reteiva')).toBe(15);
    await wait(300);
    const simulation = http.expectOne('/api/v1/pagos/simular');
    expect(simulation.request.body.invoices).toEqual([{ invoiceId: b.id, selectedConcepts: [{ conceptId: 'reteiva', percentage: 15 }] }]);
    simulation.flush({ netoAPagar: 1, retenciones: [], facturas: [] });
  });

  it('en el paso de medio de pago vuelve a facturas si se retiró una seleccionada; durante el propio pago no reacciona', async () => {
    const { app, a, b } = appWithTwoInvoices();
    app.applicationMode.set('CONSOLIDATED');
    app.currentStep.set('PAYMENT_METHOD');
    app.applyRefresh({ nit: '800092198', razon_social: 'CLIENTE', cantidad_facturas: 1, facturas: [b], valor_total_servicio: 9_500_000 }, null);
    expect(app.currentStep()).toBe('INVOICES');
    await wait(300);
    http.expectOne('/api/v1/pagos/simular').flush({ netoAPagar: 1, retenciones: [], facturas: [] });

    app.currentStep.set('PROCESSING');
    app['watchedNit'] = '800092198';
    app.onRealtimeEvent({ eventId: 'own', eventType: 'INVOICE_STATUS_CHANGED', occurredAt: '', payload: { invoiceId: b.id, newStatus: 'PAGADA' } });
    await wait(350);
    http.expectNone(r => r.url.includes('/por-nit'));
    expect(a).toBeTruthy();
  });

  it('al cambiar de NIT abandona el canal anterior', () => {
    const app = TestBed.createComponent(App).componentInstance;
    app['watchNit']('800092198');
    const before = app['unwatchNit'];
    app['watchNit']('900123456');
    expect(stompSubscriptions).toEqual(['/topic/invoices/800092198', '/topic/invoices/900123456']);
    expect(app['unwatchNit']).not.toBe(before);
    expect(app['watchedNit']).toBe('900123456');
  });
});
