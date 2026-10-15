import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { fakeAuth } from '../../../testing/fake-auth';
import { FORBIDDEN_PATH, homeRedirect, permissionGuard, requiredPermissions } from './permission.guard';

describe('permissionGuard', () => {
  function setup(permissions: string[]) {
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: AuthService, useValue: fakeAuth(permissions) }] });
  }
  const run = (guard: ReturnType<typeof permissionGuard>) =>
    TestBed.runInInjectionContext(() => guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot));

  it('/admin/users exige USERS_VIEW: sin el permiso lleva a "Sin permisos"', () => {
    setup(['INVOICES_VIEW']);
    const result = run(permissionGuard('USERS_VIEW'));
    expect(result instanceof UrlTree).toBe(true);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe(FORBIDDEN_PATH);
  });

  it('con el permiso deja pasar; con varios basta uno', () => {
    setup(['USERS_VIEW']);
    expect(run(permissionGuard('USERS_VIEW'))).toBe(true);
    expect(run(permissionGuard('ROLES_VIEW', 'USERS_VIEW'))).toBe(true);
  });

  it('expone los permisos de la ruta activa para reevaluarla cuando cambian', () => {
    const guard = permissionGuard('ROLES_VIEW');
    const child = { routeConfig: { canActivate: [guard] }, firstChild: null } as unknown as ActivatedRouteSnapshot;
    const root = { routeConfig: { canActivate: [] }, firstChild: child } as unknown as ActivatedRouteSnapshot;
    expect(requiredPermissions(root)).toEqual(['ROLES_VIEW']);
  });

  it('la ruta raíz lleva a la primera pantalla permitida (SOPORTE → Usuarios, FINANCIERA → Resumen)', () => {
    setup(['USERS_VIEW', 'ROLES_VIEW']);
    const router = TestBed.inject(Router);
    expect(router.serializeUrl(TestBed.runInInjectionContext(() => homeRedirect()))).toBe('/admin/users');
    TestBed.resetTestingModule();
    setup(['INVOICES_VIEW', 'PAYMENTS_VIEW']);
    expect(TestBed.inject(Router).serializeUrl(TestBed.runInInjectionContext(() => homeRedirect()))).toBe('/dashboard');
  });
});
