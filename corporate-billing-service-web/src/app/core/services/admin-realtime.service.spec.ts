import { TestBed } from '@angular/core/testing';
import { IMessage, StompConfig } from '@stomp/stompjs';
import { ADMIN_INVOICES_TOPIC, AdminRefreshSignal, AdminRealtimeService } from './admin-realtime.service';
import { STOMP_CLIENT_FACTORY } from './realtime.service';

describe('AdminRealtimeService', () => {
  let deliver: (body: object) => void;
  let reconnect: () => void;
  let subscribed: string[];

  beforeEach(() => {
    subscribed = [];
    TestBed.configureTestingModule({
      providers: [{
        provide: STOMP_CLIENT_FACTORY,
        useValue: (config: StompConfig) => {
          const client = {
            active: false, connected: false, reconnectDelay: 1000, connectHeaders: {},
            activate() { this.active = true; this.connected = true; config.onConnect?.({} as never); },
            deactivate() { this.active = false; },
            subscribe(destination: string, callback: (message: IMessage) => void) {
              subscribed.push(destination);
              deliver = body => callback({ body: JSON.stringify(body) } as IMessage);
              return { id: destination, unsubscribe: () => undefined };
            }
          };
          reconnect = () => config.onConnect?.({} as never);
          return client;
        }
      }]
    });
  });

  it('Caso 1/3: se suscribe una sola vez al canal administrativo y pide refrescar ante pagos y cambios de estado', () => {
    const service = TestBed.inject(AdminRealtimeService);
    const signals: AdminRefreshSignal[] = [];
    service.refreshSignals().subscribe(signal => signals.push(signal));
    service.start();
    service.start();
    expect(subscribed).toEqual([ADMIN_INVOICES_TOPIC]);

    deliver({ eventId: '1', eventType: 'INVOICE_STATUS_CHANGED', payload: { invoiceId: 'A', newStatus: 'PAGADA' } });
    deliver({ eventId: '2', eventType: 'PAYMENT_STATUS_CHANGED', payload: { newStatus: 'PENDIENTE', invoiceIds: ['A'] } });
    deliver({ eventId: '3', eventType: 'PAYMENT_STATUS_CHANGED', payload: { newStatus: 'APLICADO', invoiceIds: ['B'] } });
    deliver({ eventId: '4', eventType: 'INVOICE_PDF_AVAILABLE', payload: { invoiceId: 'C' } });
    expect(signals.filter(s => s.reason === 'event').map(s => s.event?.eventId)).toEqual(['1', '3', '4']);
    expect(signals[0].reason).toBe('reconnected'); // la conexión inicial también sincroniza por REST
  });

  it('Caso 4: al reconectar pide refrescar por REST (pudo perder eventos)', () => {
    const service = TestBed.inject(AdminRealtimeService);
    const signals: AdminRefreshSignal[] = [];
    service.refreshSignals().subscribe(signal => signals.push(signal));
    service.start();
    reconnect();
    expect(signals.some(s => s.reason === 'reconnected')).toBe(true);
  });

  it('identifica si el aviso concierne a una factura (por id o por pago que la incluye)', () => {
    const byInvoice = { reason: 'event' as const, event: { eventId: 'x', eventType: 'INVOICE_STATUS_CHANGED', occurredAt: '', payload: { invoiceId: 'A' } } };
    const byPayment = { reason: 'event' as const, event: { eventId: 'y', eventType: 'PAYMENT_STATUS_CHANGED', occurredAt: '', payload: { invoiceIds: ['B'] } } };
    expect(AdminRealtimeService.concernsInvoice(byInvoice, 'A')).toBe(true);
    expect(AdminRealtimeService.concernsInvoice(byInvoice, 'B')).toBe(false);
    expect(AdminRealtimeService.concernsInvoice(byPayment, 'B')).toBe(true);
    expect(AdminRealtimeService.concernsInvoice({ reason: 'reconnected' }, 'Z')).toBe(true);
  });
});
