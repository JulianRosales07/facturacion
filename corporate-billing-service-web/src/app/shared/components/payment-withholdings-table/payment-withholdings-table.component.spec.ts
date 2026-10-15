import { TestBed } from '@angular/core/testing';
import { PaymentWithholdingSnapshot } from '../../../core/models/payment.model';
import { PaymentWithholdingsTableComponent } from './payment-withholdings-table.component';

const base: PaymentWithholdingSnapshot = {
  conceptoId: 'c1', nombre: 'ReteFuente Servicios 4%', tipo: 'RETEFUENTE' as never, porcentaje: 4, porcentajeMaximo: 4, version: 1,
  baseCalculada: 5694150, baseMinima: 150000, aplica: true, valorRetenido: 227766, facturaId: 'f1', facturaNumero: 'F1-51993',
  porcentajeIngresado: 4, estadoSeleccion: 'APPLIED', seleccionadoEn: '2026-10-08T10:12:05-05:00', aplicadoEn: '2026-10-08T10:15:40-05:00',
  canal: 'PAYMENT_GATEWAY', seleccionadoPor: 'NIT 1088970617'
};

describe('PaymentWithholdingsTableComponent', () => {
  function render(rows: PaymentWithholdingSnapshot[]) {
    TestBed.configureTestingModule({ imports: [PaymentWithholdingsTableComponent] });
    const fixture = TestBed.createComponent(PaymentWithholdingsTableComponent);
    fixture.componentRef.setInput('withholdings', rows);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('cabe en el modal: 6 columnas compactas con fechas de selección y aplicación (hora de Colombia)', () => {
    const el = render([base]);
    expect(el.querySelectorAll('thead th').length).toBe(6);
    const row = el.querySelector('tbody tr')!.textContent!;
    expect(row).toContain('Factura F1-51993 · V1');
    expect(row).toContain('máx. 4%');
    expect(row).toContain('Seleccionada 08/10/2026 10:12:05');
    expect(row).toContain('Aplicada 08/10/2026 10:15:40');
    expect(row).toContain('Pasarela de pagos · NIT 1088970617');
  });

  it('pagos anteriores a la auditoría: canal no registrado, sin inventar actor', () => {
    const el = render([{ ...base, canal: null, seleccionadoPor: 'No registrado', aplica: false, motivoNoAplica: 'No alcanza la base mínima', estadoSeleccion: 'NOT_APPLICABLE', aplicadoEn: null }]);
    const row = el.querySelector('tbody tr')!.textContent!;
    expect(row).toContain('Canal no registrado');
    expect(row).not.toContain('No registrado ·');
    expect(row).toContain('No aplicó');
    expect(row).toContain('Aplicada —');
  });
});
