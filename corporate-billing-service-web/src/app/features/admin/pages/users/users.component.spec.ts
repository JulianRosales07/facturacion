import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { SecurityRealtimeService } from '../../../../core/services/security-realtime.service';
import { AdminUser } from '../../../../core/models/access.model';
import { fakeAuth } from '../../../../../testing/fake-auth';
import { UsersComponent } from './users.component';

const user: AdminUser = {
  id: 'u1', username: 'jperez', email: 'jperez@empresa.com', firstName: 'Juan', lastName: 'Pérez', fullName: 'Juan Pérez',
  active: true, locked: false, lastLoginAt: '2026-10-09T12:50:00Z', roles: [{ id: 'r1', name: 'ANALISTA_CARTERA', active: true }],
  version: 3, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z'
};

describe('UsersComponent', () => {
  let http: HttpTestingController;
  const changes = new Subject<null>();

  function render(permissions: string[]) {
    TestBed.configureTestingModule({
      imports: [UsersComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        { provide: AuthService, useValue: fakeAuth(permissions) },
        { provide: NotificationService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() } },
        { provide: SecurityRealtimeService, useValue: { accessChanges$: changes.asObservable() } }
      ]
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('/admin/users')).flush({ items: [user], page: 0, size: 20, totalItems: 1, totalPages: 1 });
    http.expectOne(r => r.url.endsWith('/admin/roles')).flush([]);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  const buttons = (el: HTMLElement) => Array.from(el.querySelectorAll('button')).map(b => b.textContent?.trim());

  afterEach(() => http.verify());

  it('con USERS_VIEW sin USERS_CREATE: lista usuarios pero no muestra "Nuevo usuario" ni acciones de edición', () => {
    const { el } = render(['USERS_VIEW']);
    expect(el.textContent).toContain('jperez');
    expect(el.textContent).toContain('Analista cartera');
    expect(buttons(el)).not.toContain('Nuevo usuario');
    expect(buttons(el)).toContain('Ver');
    expect(buttons(el)).not.toContain('Editar');
    expect(buttons(el)).not.toContain('Desactivar');
    expect(buttons(el)).not.toContain('Eliminar');
  });

  it('con permisos completos muestra crear, editar, desactivar y eliminar', () => {
    const { el } = render(['USERS_VIEW', 'USERS_CREATE', 'USERS_UPDATE', 'USERS_DELETE']);
    expect(buttons(el)).toEqual(expect.arrayContaining(['Nuevo usuario', 'Ver', 'Editar', 'Desactivar', 'Eliminar']));
  });

  it('desactivar pide confirmación y envía la versión para el control de concurrencia', () => {
    const { fixture } = render(['USERS_VIEW', 'USERS_UPDATE']);
    fixture.componentInstance.askStatus(user);
    expect(fixture.componentInstance.confirmation()?.kind).toBe('status');
    fixture.componentInstance.confirm();
    const req = http.expectOne(r => r.url.endsWith('/admin/users/u1/status'));
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ active: false, version: 3 });
    req.flush({ ...user, active: false });
    http.expectOne(r => r.url.endsWith('/admin/users')).flush({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
  });

  it('filtra por estado y rol en el servidor y recarga cuando otro administrador cambia accesos', async () => {
    const { fixture } = render(['USERS_VIEW']);
    fixture.componentInstance.setFilter('active', 'false');
    fixture.componentInstance.setFilter('role', 'FINANCIERA');
    await new Promise(resolve => setTimeout(resolve, 350));
    const req = http.expectOne(r => r.url.endsWith('/admin/users'));
    expect(req.request.params.get('active')).toBe('false');
    expect(req.request.params.get('role')).toBe('FINANCIERA');
    req.flush({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });

    changes.next(null);
    http.expectOne(r => r.url.endsWith('/admin/users')).flush({ items: [user], page: 0, size: 20, totalItems: 1, totalPages: 1 });
  });
});
