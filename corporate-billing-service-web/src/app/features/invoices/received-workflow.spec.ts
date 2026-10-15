import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/services/auth.service';
import { authInterceptor } from '../../core/interceptors/auth.interceptor';
import { NotificationService } from '../../core/services/notification.service';

// Recorrido integrado del router y HTTP. El proveedor externo se sustituye únicamente en la prueba.
describe('Received invoice workflow',()=>{
 it('filters and pages on the server, opens detail and fetches the protected official PDF',async()=>{
  const create=vi.fn(()=> 'blob:controlled-test-pdf'), revoke=vi.fn();
  Object.defineProperty(URL,'createObjectURL',{value:create,configurable:true});
  Object.defineProperty(URL,'revokeObjectURL',{value:revoke,configurable:true});
  const anchorClick=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>undefined);
  await TestBed.configureTestingModule({providers:[provideRouter(routes),provideHttpClient(withInterceptors([authInterceptor])),provideHttpClientTesting(),
   {provide:AuthService,useValue:{getToken:()=> 'test-access',currentUser:signal({fullName:'Operador de prueba',role:{name:'Analista'}}),logout:vi.fn(),hasPermission:()=>true,hasAnyPermission:()=>true,hasRole:()=>false}},
   {provide:NotificationService,useValue:{error:vi.fn(),success:vi.fn(),info:vi.fn(),warning:vi.fn()}}]}).compileComponents();
  const http=TestBed.inject(HttpTestingController);
  const harness=await RouterTestingHarness.create('/consultar-facturas?type=F20&page=1&vista=factura');
  const list=http.expectOne(r=>r.url.endsWith('/facturas/recibidas'));
  expect(list.request.params.has('vista')).toBe(false);
  expect(list.request.params.get('type')).toBe('F20');expect(list.request.params.get('page')).toBe('1');
  expect(list.request.headers.get('Authorization')).toBe('Bearer test-access');
  list.flush({items:[{id:'invoice-test',invoiceNumber:'287',clientNit:'900123456-1',businessName:'Cliente de prueba',billingType:'F20',issueDate:'2026-09-01',dueDate:null,total:238000,pdfAvailable:true}],page:1,size:25,totalElements:51,totalPages:3});
  harness.detectChanges();
  expect(harness.routeNativeElement?.querySelector('.page-btn[aria-current="page"]')?.textContent?.trim()).toBe('2');
  expect(harness.routeNativeElement?.querySelector('[aria-label="Página 3"]')).not.toBeNull();
  expect(harness.routeNativeElement?.textContent).toContain('51 facturas');
  await harness.navigateByUrl('/consultar-facturas/invoice-test?type=F20&page=1');
  http.expectOne(r=>r.url.endsWith('/facturas/invoice-test')).flush({id:'invoice-test',numeroFactura:'287',tipoFacturacion:'F20',fechaEmision:'2026-09-01',rutaPdf:'/api/v1/facturas/invoice-test/pdf',cliente:{nit:'900123456-1',razonSocial:'Cliente de prueba'},items:[],totales:{subtotal:200000,iva:38000,total:238000}});
  http.expectOne(r=>r.url.endsWith('/pagos/factura/invoice-test')).flush([]);
  // El detalle también trae los bloques Deuda anterior / Cargos fijos / Otros conceptos.
  const bloque=(total:number)=>({lineas:[],subtotal:total,impuestos:0,total});
  http.expectOne(r=>r.url.endsWith('/facturas/invoice-test/detalle-cobros')).flush({deudaAnterior:{cargosAnteriores:0,pagosRecibidos:0,saldo:0,facturas:0},
   cargosFijos:bloque(238000),otrosConceptos:bloque(0),totalFactura:238000,totalAPagar:238000});
  harness.detectChanges();expect(harness.routeNativeElement?.textContent).toContain('Ver PDF oficial');
  expect(harness.routeNativeElement?.textContent).toContain('Conoce los cobros a detalle');
  expect(harness.routeNativeElement?.textContent).not.toContain('Emitir Nota Crédito');
  await harness.navigateByUrl('/consultar-facturas/invoice-test/pdf');
  const pdf=http.expectOne(r=>r.url.endsWith('/facturas/invoice-test/pdf'));
  expect(pdf.request.headers.get('Authorization')).toBe('Bearer test-access');expect(pdf.request.responseType).toBe('blob');
  pdf.flush(new Blob(['%PDF-test'],{type:'application/pdf'}));harness.detectChanges();
  expect(harness.routeNativeElement?.querySelector('iframe')?.getAttribute('src')).toBe('blob:controlled-test-pdf');
  const button=Array.from(harness.routeNativeElement!.querySelectorAll('button')).find(b=>b.textContent?.includes('Descargar PDF'))!;
  button.click();http.expectOne(r=>r.url.endsWith('/facturas/invoice-test/pdf')).flush(new Blob(['%PDF-test'],{type:'application/pdf'}));
  expect(anchorClick).toHaveBeenCalled();
  await harness.navigateByUrl('/consultar-facturas?vista=factura');
  http.expectOne(r=>r.url.endsWith('/facturas/recibidas')).flush({items:[],page:0,size:25,totalElements:0,totalPages:0});
  expect(revoke).toHaveBeenCalledWith('blob:controlled-test-pdf');http.verify();anchorClick.mockRestore();
 });

 it('groups by NIT by default: one row per client, expandable invoices and the consolidated total',async()=>{
  await TestBed.configureTestingModule({providers:[provideRouter(routes),provideHttpClient(withInterceptors([authInterceptor])),provideHttpClientTesting(),
   {provide:AuthService,useValue:{getToken:()=> 'test-access',currentUser:signal({fullName:'Operador de prueba',role:{name:'Analista'}}),logout:vi.fn(),hasPermission:()=>true,hasAnyPermission:()=>true,hasRole:()=>false}},
   {provide:NotificationService,useValue:{error:vi.fn(),success:vi.fn(),info:vi.fn(),warning:vi.fn()}}]}).compileComponents();
  const http=TestBed.inject(HttpTestingController);
  const harness=await RouterTestingHarness.create('/consultar-facturas');
  const req=http.expectOne(r=>r.url.endsWith('/facturas/recibidas/por-nit'));
  expect(req.request.params.get('page')).toBe('0');
  const inv=(id:string,tipo:'F1'|'F20',consecutivo:string,valor:number)=>({id,tipo,consecutivo,clave_documento:`${tipo}:${consecutivo}`,valor_servicio:valor,
   fecha_emision:'2026-09-01',fecha_vencimiento:null,pdf_disponible:false,cufe:null});
  req.flush({items:[
   {nit:'900222222',razon_social:'Cliente varias',cantidad_facturas:3,facturas:[inv('a','F1','101',300000),inv('b','F1','102',150000),inv('c','F20','50',250000)],valor_total_servicio:700000},
   {nit:'900111111',razon_social:'Cliente una',cantidad_facturas:1,facturas:[inv('d','F1','100',200000)],valor_total_servicio:200000}],
   page:0,size:25,totalElements:2,totalPages:1});
  harness.detectChanges();
  const el=harness.routeNativeElement!;
  expect(el.querySelectorAll('tr.group-row').length).toBe(2);
  expect(el.textContent).toContain('2 clientes');
  expect(el.querySelector('table.nested')).toBeNull();
  (el.querySelector('#toggle-900222222') as HTMLButtonElement).click();harness.detectChanges();
  const nested=el.querySelectorAll('#facturas-900222222 table.nested tbody tr');
  expect(nested.length).toBe(3);
  expect(el.querySelector('#facturas-900222222')?.textContent).toContain('F20 · 50');
  (el.querySelector('#view-by-invoice') as HTMLButtonElement).click();
  await harness.fixture.whenStable();
  http.expectOne(r=>r.url.endsWith('/facturas/recibidas')).flush({items:[],page:0,size:25,totalElements:0,totalPages:0});
  http.verify();
 });
});
