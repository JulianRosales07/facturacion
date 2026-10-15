import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { DashboardComponent } from './dashboard.component';
describe('Reception dashboard',()=>{
 it('shows actual API counts and does not invent invoices when empty',async()=>{
  await TestBed.configureTestingModule({imports:[DashboardComponent],providers:[provideRouter([]),provideHttpClient(),provideHttpClientTesting()]}).compileComponents();
  const fixture=TestBed.createComponent(DashboardComponent), http=TestBed.inject(HttpTestingController);fixture.detectChanges();
  http.expectOne(r=>r.url.endsWith('/resumen')).flush({totalInvoices:0,withPdf:0,pendingPdf:0});
  http.expectOne(r=>r.url.endsWith('/facturas/recibidas')).flush({items:[],page:0,size:6,totalElements:0,totalPages:0});
  http.expectOne(r=>r.url.endsWith('/sincronizacion/estado')).flush({enEjecucion:false,ultimoCiclo:null});
  fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('Aún no se han recibido facturas');
  expect(fixture.nativeElement.textContent).not.toContain('Demo');http.verify();
 });
});
