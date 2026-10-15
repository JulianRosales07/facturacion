import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { SecurityRealtimeService } from '../../../../core/services/security-realtime.service';
import { AdminPermission, AdminRole } from '../../../../core/models/access.model';
import { fakeAuth } from '../../../../../testing/fake-auth';
import { RolesComponent, groupByModule } from './roles.component';

const permission = (id: string, code: string, module: string): AdminPermission => ({
  id, code, name: code, module, description: null, active: true, systemPermission: true, roleCount: 1, version: 0
});
const catalog = [
  permission('p1', 'INVOICES_VIEW', 'Facturación'), permission('p2', 'INVOICES_CREATE', 'Facturación'),
  permission('p3', 'PAYMENTS_VIEW', 'Pagos'), permission('p4', 'USERS_UPDATE', 'Administración'), permission('p5', 'ROLES_UPDATE', 'Administración')
];
const soporte: AdminRole = { id: 'r1', name: 'SOPORTE', description: 'Usuarios', active: true, systemRole: true, userCount: 2, permissionCount: 2, version: 1 };

describe('RolesComponent', () => {
  let http: HttpTestingController;

  function render(permissions: string[], roles: string[] = []) {
    TestBed.configureTestingModule({
      imports: [RolesComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        { provide: AuthService, useValue: fakeAuth(permissions, roles) },
        { provide: NotificationService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() } },
        { provide: SecurityRealtimeService, useValue: { accessChanges$: new Subject() } }
      ]
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(RolesComponent);
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('/admin/roles')).flush([soporte]);
    http.expectOne(r => r.url.endsWith('/admin/permissions')).flush(catalog);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => http?.verify());

  it('agrupa los permisos por módulo en vez de una lista plana', () => {
    expect(groupByModule(catalog).map(m => `${m.name}:${m.permissions.length}`))
      .toEqual(['Administración:2', 'Facturación:2', 'Pagos:1']);
  });

  it('"Seleccionar todos" marca el módulo completo y cada permiso sigue siendo individual', () => {
    const component = render(['ROLES_VIEW', 'ROLES_CREATE', 'PERMISSIONS_ASSIGN']).componentInstance;
    const billing = component.modules().find(m => m.name === 'Facturación')!;
    component.toggleModule(billing, true);
    expect(component.moduleState(billing)).toBe('all');
    component.toggle('p2', false);
    expect(component.moduleState(billing)).toBe('some');
    expect([...component.selected()]).toEqual(['p1']);
  });

  it('crear un rol envía los permisos seleccionados', () => {
    const component = render(['ROLES_VIEW', 'ROLES_CREATE', 'PERMISSIONS_ASSIGN']).componentInstance;
    component.openCreate();
    component.form.setValue({ name: 'analista cartera', description: 'Gestión de recaudo', active: true });
    component.toggle('p1', true);
    component.toggle('p3', true);
    component.save();
    const req = http.expectOne(r => r.url.endsWith('/admin/roles') && r.method === 'POST');
    expect(req.request.body).toEqual({ name: 'analista cartera', description: 'Gestión de recaudo', active: true, permissionIds: ['p1', 'p3'], version: null });
    req.flush({ ...soporte, id: 'r2', name: 'ANALISTA_CARTERA', systemRole: false });
    http.expectOne(r => r.url.endsWith('/admin/roles')).flush([]);
  });

  it('sin PERMISSIONS_ASSIGN no envía permisos (el backend lo rechazaría)', () => {
    const component = render(['ROLES_VIEW', 'ROLES_CREATE']).componentInstance;
    component.openCreate();
    component.form.setValue({ name: 'CONSULTA', description: '', active: true });
    component.save();
    const req = http.expectOne(r => r.method === 'POST');
    expect(req.request.body.permissionIds).toBeUndefined();
    req.flush({ ...soporte, name: 'CONSULTA' });
    http.expectOne(r => r.url.endsWith('/admin/roles')).flush([]);
  });

  it('advierte del auto-bloqueo cuando el administrador quita a su propio rol la gestión de usuarios', () => {
    const component = render(['ROLES_VIEW', 'ROLES_UPDATE', 'PERMISSIONS_ASSIGN'], ['SOPORTE']).componentInstance;
    component.openEdit(soporte, 'permissions');
    http.expectOne(r => r.url.endsWith('/admin/roles/r1')).flush({ ...soporte, permissions: [
      { id: 'p4', code: 'USERS_UPDATE', name: 'x', module: 'Administración' }, { id: 'p5', code: 'ROLES_UPDATE', name: 'y', module: 'Administración' }] });
    expect(component.selfLockWarning()).toBe(false);
    component.toggle('p4', false);
    expect(component.selfLockWarning()).toBe(true);
  });

  it('los roles del sistema no ofrecen "Eliminar"', () => {
    const fixture = render(['ROLES_VIEW', 'ROLES_DELETE']);
    const labels = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).map(b => b.textContent?.trim());
    expect(labels).not.toContain('Eliminar');
  });
});
