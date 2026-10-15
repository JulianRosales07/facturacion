import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Component } from '@angular/core';
import { Subject } from 'rxjs';
import { AuthService } from './auth.service';
import { RealtimeEvent, RealtimeService } from './realtime.service';
import { SECURITY_TOPIC, SecurityRealtimeService } from './security-realtime.service';
import { permissionGuard } from '../guards/permission.guard';
import { fakeAuth } from '../../../testing/fake-auth';

@Component({ standalone: true, template: '' })
class Page {}

describe('SecurityRealtimeService', () => {
  let handler: (event: RealtimeEvent) => void;
  const reconnected = new Subject<void>();

  async function setup(initial: string[], afterRefresh: string[], stillRegistered = true) {
    const auth = fakeAuth(initial);
    auth.refreshPermissions = vi.fn(async () => { auth.setPermissions(afterRefresh); return stillRegistered; });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'admin/users', component: Page, canActivate: [permissionGuard('USERS_VIEW')] },
          { path: 'sin-permisos', component: Page }
        ]),
        { provide: AuthService, useValue: auth },
        { provide: RealtimeService, useValue: {
          subscribe: (topic: string, h: (event: RealtimeEvent) => void) => { expect(topic).toBe(SECURITY_TOPIC); handler = h; return () => undefined; },
          reconnected$: reconnected.asObservable()
        } }
      ]
    });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/admin/users');
    const service = TestBed.inject(SecurityRealtimeService);
    service.start();
    return { auth, router, service };
  }

  const flush = () => new Promise(resolve => setTimeout(resolve, 0));

  it('ante USER_ROLES_CHANGED vuelve a pedir /auth/me sin cerrar sesión y conserva la pantalla si sigue permitida', async () => {
    const { auth, router } = await setup(['USERS_VIEW'], ['USERS_VIEW', 'USERS_CREATE']);
    handler({ eventId: '1', eventType: 'USER_ROLES_CHANGED', occurredAt: '', payload: { userId: 'u1' } });
    await flush();
    expect(auth.refreshPermissions).toHaveBeenCalledTimes(1);
    expect(auth.hasPermission('USERS_CREATE')).toBe(true);
    expect(router.url).toBe('/admin/users');
  });

  it('si pierde el acceso a la pantalla actual lo lleva a "Sin permisos"', async () => {
    const { router } = await setup(['USERS_VIEW'], ['INVOICES_VIEW']);
    handler({ eventId: '2', eventType: 'ROLE_PERMISSIONS_CHANGED', occurredAt: '', payload: { roleId: 'r1' } });
    await flush(); await flush();
    expect(router.url).toBe('/sin-permisos');
  });

  it('al reconectar resincroniza (pudo perder avisos) y avisa a las pantallas de administración', async () => {
    const { auth, service } = await setup(['USERS_VIEW'], ['USERS_VIEW']);
    const seen: unknown[] = [];
    service.accessChanges$.subscribe(change => seen.push(change));
    reconnected.next();
    await flush();
    expect(auth.refreshPermissions).toHaveBeenCalled();
    expect(seen).toEqual([null]);
  });
});
