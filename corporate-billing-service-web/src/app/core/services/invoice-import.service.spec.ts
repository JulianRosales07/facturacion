import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { InvoiceService } from './invoice.service';

describe('Importación de cartera para cobro', () => {
  it('envía el corte explícito y conserva los contadores reales del servidor', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    const file = new File(['excel'], 'saldos_de_cartera.xlsx');
    let updated = 0;
    TestBed.inject(InvoiceService).importPortfolio(file, '2026-10-14T15:00:00.000Z')
      .subscribe(result => { updated = result.updated; });
    const request = http.expectOne(r => r.url.endsWith('/siigo/facturas/importar-detallado'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body.get('archivo')).toBe(file);
    expect(request.request.body.get('fechaCorte')).toBe('2026-10-14T15:00:00.000Z');
    request.flush({ invoiceIds: ['documento'], created: 0, updated: 1, repeated: 0, importedBalance: '69000.00' });
    expect(updated).toBe(1);
    http.verify();
  });
});
