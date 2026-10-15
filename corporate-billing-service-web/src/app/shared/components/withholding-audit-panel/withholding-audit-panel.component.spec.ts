import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PaymentWithholdingAudit } from '../../../core/models/withholding-audit.model';
import { WithholdingAuditPanelComponent } from './withholding-audit-panel.component';

const actor = { type: 'CLIENT' as const, userId: null, username: null, name: null, nit: '800092198', sessionId: 'sess-1', label: 'NIT 800092198' };

const audit: PaymentWithholdingAudit = {
  paymentId: 'pay-001-uuid', status: 'APLICADO', applicationMode: 'CONSOLIDATED', clientNit: '800092198', clientName: 'CLIENTE',
  invoiceNumbers: ['F1-51987'], createdAt: '2026-10-09T08:35:42-05:00', confirmedAt: '2026-10-09T08:41:07-05:00',
  audit: [{
    id: 'w1', paymentId: 'pay-001-uuid', paymentStatus: 'APLICADO', clientNit: '800092198', clientName: 'CLIENTE', invoiceId: null,
    invoiceNumbers: ['F1-51987'], conceptValidityId: 'tax-123', conceptCode: 'RETEFUENTE', conceptName: 'ReteFuente Servicios', conceptVersion: 3,
    validFrom: '2026-01-01', validTo: null, enteredPercentage: 4, appliedPercentage: 4, maxPercentage: 4, calculationBase: 500000,
    minimumBase: 150000, withheldAmount: 20000, applicable: true, nonApplicableReason: null, adjustmentReason: null,
    selectionStatus: 'APPLIED', selectedAt: '2026-10-09T08:35:42-05:00', appliedAt: '2026-10-09T08:41:07-05:00',
    sourceChannel: 'PAYMENT_GATEWAY', selectedBy: actor, ipAddress: '190.1.2.3', userAgent: 'Chrome / Windows'
  }],
  events: [
    { id: 'e1', eventType: 'SELECTED', sessionId: 'sess-1', paymentId: null, invoiceId: null, conceptCode: 'RETEFUENTE', conceptName: 'ReteFuente Servicios',
      oldPercentage: null, newPercentage: 3.5, occurredAt: '2026-10-09T08:30:00-05:00', sourceChannel: 'PAYMENT_GATEWAY', actor, ipAddress: null, userAgent: null },
    { id: 'e2', eventType: 'UPDATED', sessionId: 'sess-1', paymentId: null, invoiceId: null, conceptCode: 'RETEFUENTE', conceptName: 'ReteFuente Servicios',
      oldPercentage: 3.5, newPercentage: 4, occurredAt: '2026-10-09T08:33:00-05:00', sourceChannel: 'PAYMENT_GATEWAY', actor, ipAddress: null, userAgent: null }
  ]
};

describe('WithholdingAuditPanelComponent', () => {
  it('muestra el snapshot con hora de Colombia, el detalle de auditoría y el historial de cambios', () => {
    TestBed.configureTestingModule({ imports: [WithholdingAuditPanelComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(WithholdingAuditPanelComponent);
    fixture.componentRef.setInput('paymentId', 'pay-001-uuid');
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('/admin/payments/pay-001-uuid/withholdings/audit')).flush(audit);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const row = el.querySelector('tbody tr')!.textContent!;
    expect(row).toContain('ReteFuente Servicios');
    expect(row).toContain('4%');
    expect(row).toContain('09/10/2026 08:35');
    expect(row).toContain('09/10/2026 08:41');
    expect(row).toContain('Pasarela de pagos');
    expect(row).toContain('NIT 800092198');

    (el.querySelector('tbody button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const detail = el.querySelector('.audit__detail')!.textContent!;
    expect(detail).toContain('Porcentaje ingresado4%');
    expect(detail).toContain('Porcentaje aplicado4%');
    expect(detail).toContain('09/10/2026 08:35:42');
    expect(detail).toContain('09/10/2026 08:41:07');

    const timeline = el.querySelector('.audit__timeline')!.textContent!;
    expect(timeline).toContain('Seleccionó');
    expect(timeline).toContain('3.5% → 4%');
    http.verify();
  });
});
