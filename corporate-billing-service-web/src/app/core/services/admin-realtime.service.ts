import { Injectable, inject } from '@angular/core';
import { Observable, Subject, filter, map, merge } from 'rxjs';
import { RealtimeEvent, RealtimeService } from './realtime.service';

/** Canal administrativo: cambios de facturas y pagos de todos los clientes (solo analista/administrador). */
export const ADMIN_INVOICES_TOPIC = '/topic/admin/invoices';

/** Señal para volver a consultar por REST: llegó un aviso relevante o la conexión se restableció. */
export interface AdminRefreshSignal {
  reason: 'event' | 'reconnected';
  event?: RealtimeEvent;
}

/**
 * Suscripción única del panel al canal administrativo. Las pantallas no suman ni restan montos con
 * el contenido del evento: reciben la señal y vuelven a pedir el estado real al API REST.
 */
@Injectable({ providedIn: 'root' })
export class AdminRealtimeService {
  private readonly realtime = inject(RealtimeService);
  private readonly events = new Subject<RealtimeEvent>();
  private stopSubscription: (() => void) | null = null;

  readonly state = this.realtime.state;

  /** Idempotente. Solo debe llamarse con permiso de ver facturas o pagos (el servidor rechaza al resto). */
  start(): void {
    if (this.stopSubscription) return;
    this.stopSubscription = this.realtime.subscribe(ADMIN_INVOICES_TOPIC, event => this.events.next(event));
  }

  /** Deja el canal administrativo (p. ej. si el usuario perdió el permiso). */
  stop(): void {
    this.stopSubscription?.();
    this.stopSubscription = null;
  }

  /** Avisos de cambios que afectan estados o totales, más cada reconexión (posibles eventos perdidos). */
  refreshSignals(): Observable<AdminRefreshSignal> {
    const relevant = this.events.pipe(
      filter(event => event.eventType === 'INVOICE_STATUS_CHANGED'
        || event.eventType === 'INVOICE_PDF_AVAILABLE'
        || (event.eventType === 'PAYMENT_STATUS_CHANGED' && event.payload?.['newStatus'] === 'APLICADO')),
      map(event => ({ reason: 'event' as const, event }))
    );
    const reconnected = this.realtime.reconnected$.pipe(map(() => ({ reason: 'reconnected' as const })));
    return merge(relevant, reconnected);
  }

  /** ¿El aviso menciona esta factura? (cambio de la factura o pago que la incluye). */
  static concernsInvoice(signal: AdminRefreshSignal, invoiceId: string): boolean {
    if (signal.reason === 'reconnected') return true;
    const payload = signal.event?.payload ?? {};
    if (payload['invoiceId'] === invoiceId) return true;
    const ids = payload['invoiceIds'];
    return Array.isArray(ids) && ids.includes(invoiceId);
  }
}
