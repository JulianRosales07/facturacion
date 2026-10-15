import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { InvoiceService } from '../../../core/services/invoice.service';
import { NotificationService } from '../../../core/services/notification.service';
import { ClientNoveltiesSummary, ClientNoveltyDetail } from '../../../core/models/invoice.model';
import { CopCurrencyPipe } from '../../pipes/cop-currency.pipe';
import { AppDatePipe } from '../../pipes/app-date.pipe';
import { HasPermissionDirective } from '../../directives/has-permission.directive';

/**
 * Novedades de un cliente en un periodo: estado frente a la facturación, impacto y factura
 * donde se cobraron, con la opción de reprocesarlas (idempotente).
 */
@Component({
  selector: 'app-novelties-panel',
  standalone: true,
  imports: [CommonModule, RouterModule, CopCurrencyPipe, AppDatePipe, HasPermissionDirective],
  templateUrl: './novelties-panel.component.html',
  styleUrls: ['./novelties-panel.component.scss']
})
export class NoveltiesPanelComponent {
  private readonly api = inject(InvoiceService);
  private readonly notifications = inject(NotificationService);
  private readonly destroy = inject(DestroyRef);

  readonly nit = input.required<string>();
  readonly periodo = input.required<string>();
  /** Se emite después de reprocesar, para que la página recargue la factura. */
  readonly reprocessed = output<void>();

  readonly summary = signal<ClientNoveltiesSummary | null>(null);
  readonly loading = signal(false);
  readonly reprocessing = signal(false);
  readonly error = signal('');

  /** Solo las novedades (las cuotas de instalación tienen su propio bloque en la factura). */
  readonly novelties = computed(() => (this.summary()?.novedades ?? []).filter(n => n.tipoNovedad !== 'CUOTA_INSTALACION'));
  readonly inReview = computed(() => this.novelties().filter(n => n.estado === 'EN_REVISION').length);
  readonly pendingAdjustments = computed(() => this.novelties().filter(n => n.estadoProcesamiento === 'PENDIENTE_AJUSTE' && n.estado !== 'EN_REVISION').length);

  constructor() {
    effect(() => {
      const nit = this.nit();
      const periodo = this.periodo();
      if (nit && /^\d{4}-\d{2}$/.test(periodo)) {
        untracked(() => this.load(nit, periodo));
      }
    });
  }

  load(nit = this.nit(), periodo = this.periodo()): void {
    this.loading.set(true);
    this.error.set('');
    this.api.getClientNovelties(nit, periodo).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: s => { this.summary.set(s); this.loading.set(false); },
      error: err => {
        this.loading.set(false);
        this.summary.set(null);
        this.error.set(err?.error?.mensaje || 'No se pudieron consultar las novedades.');
      }
    });
  }

  reprocess(): void {
    this.reprocessing.set(true);
    this.api.consumeClientNovelties(this.nit(), this.periodo()).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: s => {
        this.reprocessing.set(false);
        this.summary.set(s);
        this.notifications.success('Novedades reprocesadas. Repetirlo no duplica conceptos ni valores.');
        this.reprocessed.emit();
      },
      error: err => {
        this.reprocessing.set(false);
        this.notifications.error(err?.error?.mensaje || 'No se pudieron reprocesar las novedades.');
      }
    });
  }

  typeLabel(n: ClientNoveltyDetail): string {
    const labels: Record<string, string> = {
      ALTA: 'Alta de sede', BAJA: 'Baja', CAMBIO_PLAN: 'Cambio de plan', CAMBIO_SERVICIO: 'Cambio de servicio',
      SUSPENSION: 'Suspensión', REACTIVACION: 'Reactivación', TRASLADO_DIRECCION: 'Traslado de dirección'
    };
    return labels[n.tipoNovedad] ?? n.tipoNovedad;
  }

  statusLabel(n: ClientNoveltyDetail): string {
    if (n.estado === 'EN_REVISION') return 'En revisión';
    switch (n.estadoProcesamiento) {
      case 'APLICADA': return 'Aplicada';
      case 'PENDIENTE_AJUSTE': return 'Ajuste pendiente';
      case 'AJUSTE_APLICADO': return 'Ajuste aplicado';
      case 'PENDIENTE': return 'Pendiente de facturar';
      default: return n.estado === 'CONSUMIDA' ? 'Aplicada' : 'Pendiente de facturar';
    }
  }

  statusClass(n: ClientNoveltyDetail): string {
    if (n.estado === 'EN_REVISION') return 'status--review';
    if (n.estadoProcesamiento === 'APLICADA' || n.estadoProcesamiento === 'AJUSTE_APLICADO') return 'status--done';
    return 'status--pending';
  }

  /** Impacto con signo: positivo cobra, negativo es a favor del cliente. */
  signedImpact(n: ClientNoveltyDetail): number | null {
    if (n.tipoImpacto === 'INFORMATIVO') return null;
    return n.tipoImpacto === 'RESTA' ? -n.valorAjuste : n.valorAjuste;
  }
}
