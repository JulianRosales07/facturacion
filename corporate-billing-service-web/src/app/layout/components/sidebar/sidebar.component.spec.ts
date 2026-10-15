import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { SidebarComponent } from './sidebar.component';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { fakeAuth } from '../../../../testing/fake-auth';

describe('SidebarComponent', () => {
  let component: SidebarComponent;
  let fixture: ComponentFixture<SidebarComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SidebarComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideAnimationsAsync(),
        AuthService,
        NotificationService
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(SidebarComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should have initial collapsed state as false', () => {
    expect(component.collapsed).toBe(false);
  });

  it('should toggle theme mode', () => {
    component.toggleTheme(true);
    expect(component.isDarkMode()).toBe(true);
    component.toggleTheme(false);
    expect(component.isDarkMode()).toBe(false);
  });
});

describe('SidebarComponent — navegación según permisos', () => {
  function sections(permissions: string[]) {
    TestBed.configureTestingModule({
      imports: [SidebarComponent],
      providers: [provideRouter([]), provideHttpClient(), provideAnimationsAsync(),
        { provide: AuthService, useValue: fakeAuth(permissions) }, NotificationService]
    });
    return TestBed.createComponent(SidebarComponent).componentInstance.navSections()
      .map(section => `${section.title}: ${section.items.map(item => item.label).join(', ')}`);
  }

  it('SOPORTE solo ve Administración', () => {
    expect(sections(['USERS_VIEW', 'ROLES_VIEW', 'PERMISSIONS_VIEW'])).toEqual(['Administración: Usuarios, Roles, Permisos']);
  });

  it('FINANCIERA no ve Administración', () => {
    const visible = sections(['INVOICES_VIEW', 'PAYMENTS_VIEW', 'CARTERA_VIEW', 'TAX_CONCEPTS_VIEW', 'SYNC_VIEW', 'IMPORTS_VIEW']);
    expect(visible.some(section => section.startsWith('Administración'))).toBe(false);
    expect(visible).toContain('Operación diaria: Resumen de recepción, Facturas recibidas, Importación de órdenes, Sincronización e historial');
    expect(visible).toContain('Cartera y tributos: Cartera, Pagos y retenciones, Catálogo tributario');
  });
});
