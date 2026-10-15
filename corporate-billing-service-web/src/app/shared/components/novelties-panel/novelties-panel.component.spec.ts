import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { ClientNoveltiesSummary } from '../../../core/models/invoice.model';
import { NoveltiesPanelComponent } from './novelties-panel.component';

describe('NoveltiesPanelComponent — estado, impacto y factura de cada novedad', () => {
  const resumen: ClientNoveltiesSummary = {
    periodo: '2026-10',
    totalNovedades: 3,
    baseMensual: 180000,
    ajusteNovedades: 25000,
    cuotasInstalacion: 0,
    totalEstimadoFactura: 205000,
    novedades: [
      {
        id: 'n1', tipoNovedad: 'CAMBIO_PLAN', sedeNombre: 'SEDE PRINCIPAL', codigoOrden: 'PRB-860-A',
        fechaEfectiva: '2026-10-16', diasProrrateo: 15, valorOriginal: 180000, valorNuevo: 250000,
        valorAjuste: 35000, tipoImpacto: 'SUMA', observacion: 'Upgrade', estado: 'CONSUMIDA',
        estadoProcesamiento: 'APLICADA', periodoNovedad: '2026-10', facturaId: 'f-1', numeroFactura: 'FESP 53098'
      },
      {
        id: 'n2', tipoNovedad: 'CAMBIO_PLAN', sedeNombre: 'SEDE PRINCIPAL', codigoOrden: 'PRB-860-A',
        fechaEfectiva: '2026-10-25', diasProrrateo: 6, valorOriginal: 250000, valorNuevo: 200000,
        valorAjuste: 10000, tipoImpacto: 'RESTA', observacion: 'Bajada', estado: 'PENDIENTE_FACTURAR',
        estadoProcesamiento: 'PENDIENTE_AJUSTE', periodoNovedad: '2026-10',
        detalleProcesamiento: 'La factura FESP 53098 tiene pagos aplicados. Ajuste de -10000.00 para la siguiente factura'
      },
      {
        id: 'n3', tipoNovedad: 'CAMBIO_PLAN', sedeNombre: 'SEDE BODEGA', codigoOrden: 'PRB-860-B',
        fechaEfectiva: '2026-10-20', diasProrrateo: 11, valorOriginal: 120000, valorNuevo: 150000,
        valorAjuste: 0, tipoImpacto: 'INFORMATIVO', observacion: 'no cobrar prorrateo', estado: 'EN_REVISION',
        estadoProcesamiento: 'PENDIENTE_AJUSTE', periodoNovedad: '2026-10',
        detalleProcesamiento: 'Impacto no calculable: requiere revisión manual'
      }
    ]
  };

  let http: HttpTestingController;
  const notifications = { error: vi.fn(), success: vi.fn(), info: vi.fn(), warning: vi.fn() };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NoveltiesPanelComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { hasPermission: () => true, hasAnyPermission: () => true, currentUser: signal(null) } },
        { provide: NotificationService, useValue: notifications }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render() {
    const fixture = TestBed.createComponent(NoveltiesPanelComponent);
    fixture.componentRef.setInput('nit', '860074450');
    fixture.componentRef.setInput('periodo', '2026-10');
    fixture.detectChanges();
    http.expectOne(r => r.url.includes('/sincronizacion/novedades/clientes/860074450') && r.params.get('periodo') === '2026-10')
      .flush(resumen);
    fixture.detectChanges();
    return fixture;
  }

  it('muestra cada novedad con su estado, su impacto y la factura donde se cobró', () => {
    const el: HTMLElement = render().nativeElement;
    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('Aplicada');
    expect(rows[0].querySelector('a')?.textContent).toContain('FESP 53098');
    expect(rows[1].textContent).toContain('Ajuste pendiente');
    expect(rows[1].textContent).toContain('siguiente factura');
    expect(rows[2].textContent).toContain('En revisión');
    expect(rows[2].textContent).toContain('Por revisar');
  });

  it('resalta las novedades en revisión y los ajustes que esperan la siguiente factura', () => {
    const el: HTMLElement = render().nativeElement;
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('1 novedad(es) en revisión');
    expect(el.querySelector('[role="status"]')?.textContent).toContain('1 ajuste(s) esperando');
    expect(el.querySelector('tr.row--review')).not.toBeNull();
  });

  it('reprocesar llama al reproceso y avisa a la página para recargar la factura', () => {
    const fixture = render();
    const emitted = vi.fn();
    fixture.componentInstance.reprocessed.subscribe(emitted);

    const button = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(b => b.textContent?.includes('Reprocesar'))!;
    button.click();

    const req = http.expectOne(r => r.url.includes('/sincronizacion/consumir-novedades'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ nit: '860074450', periodo: '2026-10' });
    req.flush(resumen);

    expect(emitted).toHaveBeenCalledOnce();
    expect(notifications.success).toHaveBeenCalled();
  });
});
