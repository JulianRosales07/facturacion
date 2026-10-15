import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { AuthService } from '../../../../core/services/auth.service';
import { authInterceptor } from '../../../../core/interceptors/auth.interceptor';
import { NotificationService } from '../../../../core/services/notification.service';
import { ClientInvoiceGroup, GroupedInvoice } from '../../../../core/models/incoming-invoice.model';
import { InvoiceListComponent } from './invoice-list.component';

describe('InvoiceListComponent — Prueba de variación de valor por Novedades a mitad de mes', () => {
  const nitCliente = '900245045';

  // 1. Factura original emitida a principios de mes (Canon ordinario - $ 13.161.400, Pagada)
  const facturaBase: GroupedInvoice = {
    id: 'f-50776',
    tipo: 'F1',
    consecutivo: '50776',
    clave_documento: 'F1:50776',
    valor_servicio: 13161400,
    fecha_emision: '2026-08-08',
    fecha_vencimiento: '2026-08-13',
    pdf_disponible: true,
    cufe: 'CUFE-INICIAL-50776',
    estado_pago: 'PAGADA'
  };

  // 2. Factura emitida por novedad / aumento de servicio a mitad de mes ($ 13.161.400, Pendiente)
  const facturaNovedad: GroupedInvoice = {
    id: 'f-50719',
    tipo: 'F1',
    consecutivo: '50719',
    clave_documento: 'F1:50719',
    valor_servicio: 13161400,
    fecha_emision: '2026-08-15',
    fecha_vencimiento: '2026-08-30',
    pdf_disponible: true,
    cufe: 'CUFE-NOVEDAD-50719',
    estado_pago: 'PENDIENTE'
  };

  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [InvoiceListComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            getToken: () => 'test-token',
            currentUser: signal({ fullName: 'Operador Pruebas', role: { name: 'Analista' } }),
            logout: vi.fn(),
            hasPermission: () => true,
            hasAnyPermission: () => true,
            hasRole: () => false
          }
        },
        {
          provide: NotificationService,
          useValue: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warning: vi.fn() }
        }
      ]
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('debe reflejar el cambio en valor total y saldo pendiente cuando ingresa una novedad a mitad de mes', () => {
    const fixture = TestBed.createComponent(InvoiceListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();

    // 1. Carga inicial del backend: solo la factura base
    const reqInicial = http.expectOne(r => r.url.endsWith('/facturas/recibidas/por-nit'));
    const grupoInicial: ClientInvoiceGroup = {
      nit: nitCliente,
      razon_social: null,
      cantidad_facturas: 1,
      facturas: [facturaBase],
      valor_total_servicio: 13161400,
      valor_pagado: 13161400,
      valor_pendiente: 0
    };

    reqInicial.flush({
      items: [grupoInicial],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1
    });
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;

    // Verificación estado inicial: 1 factura, total $ 13.161.400, pendiente $ 0
    expect(el.textContent).toContain(`NIT ${nitCliente}`);
    expect(el.textContent).toContain('13.161.400');
    expect(el.querySelector('.count-pill')?.textContent?.trim()).toBe('1');
    expect(comp.isExpanded(nitCliente)).toBe(true);

    expect(el.querySelectorAll(`#facturas-${nitCliente} table.nested tbody tr`).length).toBe(1);
    expect(el.querySelector(`#facturas-${nitCliente}`)?.textContent).toContain('Total del cliente (1 factura)');

    // 2. Simulamos la llegada de la novedad a mitad de mes
    // El valor_total_servicio aumenta a $ 26.322.800 y el pendiente pasa a $ 13.161.400
    const grupoConNovedad: ClientInvoiceGroup = {
      nit: nitCliente,
      razon_social: null,
      cantidad_facturas: 2,
      facturas: [facturaBase, facturaNovedad],
      valor_total_servicio: 26322800,
      valor_pagado: 13161400,
      valor_pendiente: 13161400
    };

    // Actualizamos los datos (por ejemplo, recarga tras notificación o refresco de listado)
    comp.grouped.set({
      items: [grupoConNovedad],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1
    });
    fixture.detectChanges();

    // 3. Validaciones tras el aumento del servicio / novedad:
    // a) El count pill ahora muestra 2 facturas
    expect(el.querySelector('.count-pill')?.textContent?.trim()).toBe('2');

    // b) En el acordeón del cliente ahora existen 2 filas de facturas (50776 y 50719)
    const filasFacturas = el.querySelectorAll(`#facturas-${nitCliente} table.nested tbody tr`);
    expect(filasFacturas.length).toBe(2);
    expect(el.querySelector(`#facturas-${nitCliente}`)?.textContent).toContain('50776');
    expect(el.querySelector(`#facturas-${nitCliente}`)?.textContent).toContain('50719');

    // c) En el pie de tabla se actualiza el resumen del cliente:
    const tfootTexto = el.querySelector(`#facturas-${nitCliente} table.nested tfoot`)?.textContent || '';
    expect(tfootTexto).toContain('Total del cliente (2 facturas)');
    expect(tfootTexto).toContain('26.322.800'); // Nuevo total con la novedad
    expect(tfootTexto).toContain('Pagado:');
    expect(tfootTexto).toContain('Pendiente:');
    expect(tfootTexto).toContain('13.161.400');
  });
});
