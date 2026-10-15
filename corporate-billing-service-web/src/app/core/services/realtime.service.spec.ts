import { TestBed } from '@angular/core/testing';
import { IMessage, StompConfig, StompSubscription } from '@stomp/stompjs';
import { REALTIME_TOKEN_PROVIDER, RealtimeEvent, RealtimeService, STOMP_CLIENT_FACTORY, StompClientLike } from './realtime.service';

/** Cliente STOMP simulado: permite disparar conexión, cierre y mensajes desde la prueba. */
class FakeStompClient implements StompClientLike {
  active = false;
  connected = false;
  reconnectDelay: number;
  connectHeaders: Record<string, string> = {};
  readonly subscriptions = new Map<string, (message: IMessage) => void>();
  readonly unsubscribed: string[] = [];

  constructor(readonly config: StompConfig) {
    this.reconnectDelay = config.reconnectDelay ?? 0;
  }

  activate(): void { this.active = true; }
  deactivate(): void { this.active = false; this.connected = false; }

  subscribe(destination: string, callback: (message: IMessage) => void): StompSubscription {
    this.subscriptions.set(destination, callback);
    return { id: destination, unsubscribe: () => { this.unsubscribed.push(destination); this.subscriptions.delete(destination); } };
  }

  /** Simula el CONNECTED del servidor. */
  open(): void {
    (this.config.beforeConnect as () => void)?.();
    this.connected = true;
    this.config.onConnect?.({} as never);
  }

  /** Simula una caída de la conexión (internet, reinicio del backend, proxy). */
  drop(): void {
    this.connected = false;
    this.subscriptions.clear();
    this.config.onWebSocketClose?.({} as never);
  }

  push(destination: string, event: Partial<RealtimeEvent>): void {
    this.subscriptions.get(destination)?.({ body: JSON.stringify(event) } as IMessage);
  }
}

describe('RealtimeService', () => {
  let client: FakeStompClient;
  let service: RealtimeService;
  let token: string | null;

  beforeEach(() => {
    token = 'jwt-1';
    TestBed.configureTestingModule({
      providers: [
        { provide: STOMP_CLIENT_FACTORY, useValue: (config: StompConfig) => (client = new FakeStompClient(config)) },
        { provide: REALTIME_TOKEN_PROVIDER, useValue: () => token }
      ]
    });
    service = TestBed.inject(RealtimeService);
  });

  it('abre una sola conexión, envía el token Bearer y heartbeats, y pasa a CONECTADO', () => {
    service.subscribe('/topic/invoices/800092198', () => undefined);
    service.subscribe('/topic/invoices/800092198', () => undefined);
    expect(client.config.heartbeatIncoming).toBe(10000);
    expect(client.config.heartbeatOutgoing).toBe(10000);
    expect(service.state()).toBe('RECONECTANDO');

    client.open();
    expect(client.connectHeaders['Authorization']).toBe('Bearer jwt-1');
    expect(service.state()).toBe('CONECTADO');
    expect([...client.subscriptions.keys()]).toEqual(['/topic/invoices/800092198']);
  });

  it('reconecta con espera creciente (1 s, 2 s, 5 s, 10 s) y la reinicia al reconectar', () => {
    service.subscribe('/topic/admin/invoices', () => undefined);
    client.open();
    const delays: number[] = [];
    for (let i = 0; i < 5; i++) {
      client.drop();
      delays.push(client.reconnectDelay);
    }
    expect(service.state()).toBe('RECONECTANDO');
    expect(delays).toEqual([2000, 5000, 10000, 10000, 10000]);
    expect(RealtimeService.BACKOFF_MS[0]).toBe(1000);

    client.open();
    expect(client.reconnectDelay).toBe(1000);
    expect(service.state()).toBe('CONECTADO');
  });

  it('al reconectar restaura las suscripciones y avisa para resincronizar por REST', () => {
    const received: RealtimeEvent[] = [];
    let resyncs = 0;
    service.reconnected$.subscribe(() => resyncs++);
    service.subscribe('/topic/invoices/800092198', event => received.push(event));
    client.open();
    client.drop();
    token = 'jwt-2';
    client.open();

    expect(resyncs).toBe(2);
    expect(client.connectHeaders['Authorization']).toBe('Bearer jwt-2');
    client.push('/topic/invoices/800092198', { eventId: 'e1', eventType: 'INVOICE_STATUS_CHANGED', payload: {} });
    expect(received.map(e => e.eventId)).toEqual(['e1']);
  });

  it('descarta eventos repetidos por eventId', () => {
    const received: RealtimeEvent[] = [];
    service.subscribe('/topic/invoices/1', event => received.push(event));
    client.open();
    client.push('/topic/invoices/1', { eventId: 'dup', eventType: 'INVOICE_STATUS_CHANGED', payload: {} });
    client.push('/topic/invoices/1', { eventId: 'dup', eventType: 'INVOICE_STATUS_CHANGED', payload: {} });
    client.push('/topic/invoices/1', { eventId: 'otro', eventType: 'INVOICE_STATUS_CHANGED', payload: {} });
    expect(received.map(e => e.eventId)).toEqual(['dup', 'otro']);
  });

  it('al cambiar de NIT se desuscribe del canal anterior', () => {
    const stopA = service.subscribe('/topic/invoices/A', () => undefined);
    client.open();
    stopA();
    service.subscribe('/topic/invoices/B', () => undefined);
    expect(client.unsubscribed).toEqual(['/topic/invoices/A']);
    expect([...client.subscriptions.keys()]).toEqual(['/topic/invoices/B']);

    client.drop();
    client.open();
    expect([...client.subscriptions.keys()]).toEqual(['/topic/invoices/B']);
  });

  it('disconnect deja el estado DESCONECTADO', () => {
    service.subscribe('/topic/invoices/1', () => undefined);
    client.open();
    service.disconnect();
    expect(service.state()).toBe('DESCONECTADO');
    expect(client.active).toBe(false);
  });
});
