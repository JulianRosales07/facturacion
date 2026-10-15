import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NotificationService } from '../../../../core/services/notification.service';
import { PaymentService } from '../../../../core/services/payment.service';
import { WithholdingAuditComponent } from './withholding-audit.component';

describe('WithholdingAuditComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [WithholdingAuditComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: NotificationService, useValue: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() } }]
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('busca con los filtros de auditoría (NIT, factura, pago, concepto, usuario, canal y fechas) y pagina', () => {
    const fixture = TestBed.createComponent(WithholdingAuditComponent);
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('/admin/withholdings/audit')).flush({ items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 });

    const component = fixture.componentInstance;
    component.filter = { nit: '800092198', invoice: 'F1-51987', paymentId: 'PAY-001', concept: 'ReteFuente', user: 'jperez',
      channel: 'PAYMENT_GATEWAY', from: '2026-10-01', to: '2026-10-09' };
    component.search();
    const req = http.expectOne(r => r.url.endsWith('/admin/withholdings/audit'));
    expect(req.request.params.get('nit')).toBe('800092198');
    expect(req.request.params.get('invoice')).toBe('F1-51987');
    expect(req.request.params.get('paymentId')).toBe('PAY-001');
    expect(req.request.params.get('concept')).toBe('ReteFuente');
    expect(req.request.params.get('user')).toBe('jperez');
    expect(req.request.params.get('channel')).toBe('PAYMENT_GATEWAY');
    expect(req.request.params.get('from')).toBe('2026-10-01');
    expect(req.request.params.get('to')).toBe('2026-10-09');
    expect(req.request.params.get('page')).toBe('0');
    req.flush({ items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 });
  });

  it('"Exportar Excel" descarga el .xlsx con los mismos filtros de la búsqueda', () => {
    (URL as unknown as { createObjectURL: () => string }).createObjectURL = () => 'blob:xlsx';
    (URL as unknown as { revokeObjectURL: () => void }).revokeObjectURL = () => undefined;
    const fixture = TestBed.createComponent(WithholdingAuditComponent);
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('/admin/withholdings/audit')).flush({ items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 });
    fixture.componentInstance.filter = { nit: '800092198', channel: 'ADMIN_WEB' };
    fixture.componentInstance.export('xlsx');
    expect(fixture.componentInstance.exporting()).toBe('xlsx');
    const req = http.expectOne(r => r.url.endsWith('/admin/withholdings/audit/export'));
    expect(req.request.params.get('format')).toBe('xlsx');
    expect(req.request.params.get('nit')).toBe('800092198');
    expect(req.request.params.get('channel')).toBe('ADMIN_WEB');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['PK']));
    expect(fixture.componentInstance.exporting()).toBeNull();
  });

  it('no consulta con un rango de fechas invertido', () => {
    const fixture = TestBed.createComponent(WithholdingAuditComponent);
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('/admin/withholdings/audit')).flush({ items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 });
    fixture.componentInstance.filter = { from: '2026-10-09', to: '2026-10-01' };
    fixture.componentInstance.search();
    http.expectNone(r => r.url.endsWith('/admin/withholdings/audit'));
  });

  it('permite cambiar el tamaño de página y recalcula páginas para el paginador', () => {
    const fixture = TestBed.createComponent(WithholdingAuditComponent);
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('/admin/withholdings/audit')).flush({ items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 });

    const comp = fixture.componentInstance;
    comp.changePageSize(25);
    const req = http.expectOne(r => r.url.endsWith('/admin/withholdings/audit'));
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.get('page')).toBe('0');
    req.flush({ items: [], page: 0, size: 25, totalItems: 25, totalPages: 1 });

    expect(comp.pageNumbers(0, 5)).toEqual([0, 1, 2, 3, 4]);
    expect(comp.pageNumbers(0, 20)).toEqual([0, 1, 2, 3, 4, null, 19]);
    expect(comp.pageNumbers(10, 20)).toEqual([0, null, 9, 10, 11, null, 19]);
  });
});

describe('PaymentService — liquidación desde el panel', () => {
  it('declara el canal ADMIN_WEB con una sesión de selección que se renueva al registrar el pago', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    const payments = TestBed.inject(PaymentService);

    payments.simulatePayment({} as never).subscribe();
    const sim = http.expectOne(r => r.url.endsWith('/pagos/simular'));
    expect(sim.request.headers.get('X-Client-Channel')).toBe('ADMIN_WEB');
    const session = sim.request.headers.get('X-Selection-Session');
    expect(session).toBeTruthy();
    sim.flush({});

    payments.registerPayment({} as never).subscribe();
    const register = http.expectOne(r => r.url.endsWith('/pagos'));
    expect(register.request.headers.get('X-Selection-Session')).toBe(session);
    register.flush({});
    expect(payments.currentSelectionSession).not.toBe(session);
    http.verify();
  });
});
