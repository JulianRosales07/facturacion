import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { GatewayService } from './gateway.service';
import { ClientInvoiceGroup, PaymentForm, RetentionSelectionPayload } from '../models/gateway.model';

describe('GatewayService — auditoría de retenciones', () => {
  let service: GatewayService;
  let http: HttpTestingController;
  const selection = { applicationMode: 'CONSOLIDATED', invoiceIds: ['f1'], selectedConcepts: [{ conceptId: 'rf', percentage: 4 }] } as unknown as RetentionSelectionPayload;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(GatewayService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('simular y checkout declaran el canal PAYMENT_GATEWAY y la misma sesión; nunca envían la hora', () => {
    service.simulate('800092198', selection).subscribe();
    const sim = http.expectOne('/api/v1/pagos/simular');
    expect(sim.request.headers.get('X-Client-Channel')).toBe('PAYMENT_GATEWAY');
    const session = sim.request.headers.get('X-Selection-Session');
    expect(session).toBeTruthy();
    expect(JSON.stringify(sim.request.body)).not.toMatch(/selectedAt|enteredAt|fecha/i);
    sim.flush({});

    const group = { nit: '800092198', razon_social: 'CLIENTE', cantidad_facturas: 1, facturas: [], valor_total_servicio: 0 } as ClientInvoiceGroup;
    service.processPayment(group, [], selection, { method: 'PSE', bankCode: '1007' } as PaymentForm).subscribe();
    const checkout = http.expectOne('/api/v1/pagos/checkout');
    expect(checkout.request.headers.get('X-Client-Channel')).toBe('PAYMENT_GATEWAY');
    expect(checkout.request.headers.get('X-Selection-Session')).toBe(session);
    checkout.flush({ pagoId: 'p1', netoAPagar: 100, liquidacion: {} });
    http.expectOne('/api/v1/pagos/confirmar').flush({ paymentId: 'p1', status: 'APLICADO', paidInvoiceIds: [] });

    // Tras el pago, el siguiente proceso tiene su propio historial.
    expect(service.currentSelectionSession).not.toBe(session);
  });
});
