import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { Client, IMessage, StompConfig, StompSubscription } from '@stomp/stompjs';
import { Subject } from 'rxjs';

/** Aviso del backend. No es la fuente de verdad: indica que algo cambió para volver a consultar por REST. */
export interface RealtimeEvent {
  eventId: string;
  eventType: 'INVOICE_STATUS_CHANGED' | 'INVOICE_PDF_AVAILABLE' | 'PAYMENT_STATUS_CHANGED' | string;
  occurredAt: string;
  payload: Record<string, unknown>;
}

export type RealtimeConnectionState = 'CONECTADO' | 'RECONECTANDO' | 'DESCONECTADO';

/** Mínimo que el servicio usa del cliente STOMP (permite sustituirlo en pruebas). */
export interface StompClientLike {
  active: boolean;
  connected: boolean;
  reconnectDelay: number;
  connectHeaders: Record<string, string>;
  activate(): void;
  deactivate(): Promise<void> | void;
  subscribe(destination: string, callback: (message: IMessage) => void): StompSubscription;
}

export const STOMP_CLIENT_FACTORY = new InjectionToken<(config: StompConfig) => StompClientLike>('STOMP_CLIENT_FACTORY', {
  providedIn: 'root',
  factory: () => (config: StompConfig) => new Client(config)
});

/** Token Bearer para el CONNECT (mismo JWT del API REST); null si la app no maneja sesión. */
export const REALTIME_TOKEN_PROVIDER = new InjectionToken<() => string | null>('REALTIME_TOKEN_PROVIDER', {
  providedIn: 'root',
  factory: () => () => null
});

type Handler = (event: RealtimeEvent) => void;

/**
 * Conexión WebSocket/STOMP única para toda la aplicación.
 * - Reconecta sola con espera creciente (1 s, 2 s, 5 s, 10 s) y restaura las suscripciones.
 * - Emite {@link reconnected$} en cada (re)conexión: los eventos ocurridos mientras estuvo
 *   desconectada no se reciben, así que quien escucha debe volver a consultar el estado por REST.
 * - Descarta eventos repetidos por eventId.
 */
@Injectable({ providedIn: 'root' })
export class RealtimeService {
  static readonly BACKOFF_MS = [1000, 2000, 5000, 10000];
  static readonly HEARTBEAT_MS = 10000;

  private readonly createClient = inject(STOMP_CLIENT_FACTORY);
  private readonly tokenProvider = inject(REALTIME_TOKEN_PROVIDER);

  readonly state = signal<RealtimeConnectionState>('DESCONECTADO');
  readonly reconnected$ = new Subject<void>();

  private client: StompClientLike | null = null;
  private attempt = 0;
  private readonly topics = new Map<string, { handlers: Set<Handler>; subscription: StompSubscription | null }>();
  private readonly seenIds: string[] = [];
  private readonly seen = new Set<string>();

  connect(): void {
    if (this.client?.active) return;
    const client = this.createClient({
      brokerURL: RealtimeService.brokerUrl(),
      heartbeatIncoming: RealtimeService.HEARTBEAT_MS,
      heartbeatOutgoing: RealtimeService.HEARTBEAT_MS,
      reconnectDelay: RealtimeService.BACKOFF_MS[0],
      // El token se lee en cada intento: tras reconectar se usa el vigente.
      beforeConnect: () => {
        const token = this.tokenProvider();
        client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
      },
      onConnect: () => this.handleConnected(),
      onWebSocketClose: () => this.handleClosed(),
      onStompError: frame => console.warn('[realtime] error STOMP', frame.headers['message']),
      debug: () => undefined
    });
    this.client = client;
    this.state.set('RECONECTANDO');
    client.activate();
  }

  disconnect(): void {
    const client = this.client;
    this.client = null;
    this.topics.forEach(entry => entry.subscription = null);
    this.state.set('DESCONECTADO');
    void client?.deactivate();
  }

  /** Se suscribe al topic (abre la conexión si hace falta). Devuelve la función para desuscribirse. */
  subscribe(topic: string, handler: Handler): () => void {
    let entry = this.topics.get(topic);
    if (!entry) {
      entry = { handlers: new Set(), subscription: null };
      this.topics.set(topic, entry);
    }
    entry.handlers.add(handler);
    if (this.client?.connected && !entry.subscription) {
      entry.subscription = this.client.subscribe(topic, message => this.dispatch(topic, message));
    }
    this.connect();
    return () => this.unsubscribe(topic, handler);
  }

  private unsubscribe(topic: string, handler: Handler): void {
    const entry = this.topics.get(topic);
    if (!entry) return;
    entry.handlers.delete(handler);
    if (entry.handlers.size === 0) {
      entry.subscription?.unsubscribe();
      this.topics.delete(topic);
    }
  }

  private handleConnected(): void {
    this.attempt = 0;
    if (this.client) this.client.reconnectDelay = RealtimeService.BACKOFF_MS[0];
    this.state.set('CONECTADO');
    // Las suscripciones STOMP no sobreviven a una reconexión: se restauran todas.
    this.topics.forEach((entry, topic) => {
      entry.subscription = this.client!.subscribe(topic, message => this.dispatch(topic, message));
    });
    this.reconnected$.next();
  }

  private handleClosed(): void {
    this.topics.forEach(entry => entry.subscription = null);
    if (!this.client) {
      this.state.set('DESCONECTADO');
      return;
    }
    this.state.set('RECONECTANDO');
    this.attempt++;
    this.client.reconnectDelay = RealtimeService.BACKOFF_MS[Math.min(this.attempt, RealtimeService.BACKOFF_MS.length - 1)];
  }

  private dispatch(topic: string, message: IMessage): void {
    let event: RealtimeEvent;
    try {
      event = JSON.parse(message.body) as RealtimeEvent;
    } catch {
      return;
    }
    if (event.eventId) {
      if (this.seen.has(event.eventId)) return;
      this.seen.add(event.eventId);
      this.seenIds.push(event.eventId);
      if (this.seenIds.length > 500) this.seen.delete(this.seenIds.shift()!);
    }
    this.topics.get(topic)?.handlers.forEach(handler => handler(event));
  }

  /** Mismo host que la app (en desarrollo el proxy de ng serve reenvía /ws al backend). */
  static brokerUrl(): string {
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    return `${protocol}://${location.host}/ws`;
  }
}
