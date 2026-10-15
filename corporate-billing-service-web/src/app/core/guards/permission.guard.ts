import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router, UrlTree } from '@angular/router';
import { AuthService } from '../services/auth.service';

/** Ruta "sin permisos" (403). */
export const FORBIDDEN_PATH = '/sin-permisos';

/**
 * Exige al menos uno de los permisos indicados. Es solo experiencia de usuario: el backend
 * vuelve a validar cada petición. Los permisos quedan en `data.permissions` para poder
 * reevaluar la ruta actual cuando cambian (ver SecurityRealtimeService).
 */
export function permissionGuard(...permissions: string[]): CanActivateFn {
  const guard: CanActivateFn = () => {
    const auth = inject(AuthService);
    return auth.hasAnyPermission(permissions) ? true : inject(Router).createUrlTree([FORBIDDEN_PATH]);
  };
  (guard as GuardWithPermissions).permissions = permissions;
  return guard;
}

interface GuardWithPermissions { permissions?: string[]; }

/** Permisos exigidos por la ruta activa más profunda (vacío si no exige ninguno). */
export function requiredPermissions(snapshot: ActivatedRouteSnapshot): string[] {
  let route: ActivatedRouteSnapshot | null = snapshot;
  let required: string[] = [];
  while (route) {
    for (const guard of route.routeConfig?.canActivate ?? []) {
      const permissions = (guard as GuardWithPermissions).permissions;
      if (permissions) required = permissions;
    }
    route = route.firstChild;
  }
  return required;
}

/** Primera pantalla a la que el usuario tiene acceso (para la ruta raíz y rutas desconocidas). */
export const HOME_ROUTES: { path: string; permissions: string[] }[] = [
  { path: '/dashboard', permissions: ['INVOICES_VIEW'] },
  { path: '/pagos', permissions: ['PAYMENTS_VIEW'] },
  { path: '/cartera', permissions: ['CARTERA_VIEW'] },
  { path: '/admin/users', permissions: ['USERS_VIEW'] },
  { path: '/admin/roles', permissions: ['ROLES_VIEW'] },
  { path: '/admin/permissions', permissions: ['PERMISSIONS_VIEW'] }
];

export function homeRedirect(): UrlTree {
  const auth = inject(AuthService);
  const router = inject(Router);
  const home = HOME_ROUTES.find(route => auth.hasAnyPermission(route.permissions));
  return router.parseUrl(home ? home.path : '/perfil');
}
