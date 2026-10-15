import { computed, signal } from '@angular/core';

/** AuthService simulado: permisos y roles controlables desde la prueba. */
export function fakeAuth(permissions: string[] = [], roles: string[] = [], username = 'admin.pruebas') {
  const perms = signal(new Set(permissions));
  const roleList = signal(roles);
  const fake = {
    permissions: perms,
    roles: roleList,
    currentUser: computed(() => ({ username, fullName: 'Admin Pruebas', role: { name: 'Admin' }, roles: roleList(), permissions: [...perms()] })),
    getToken: () => 'token',
    hasPermission: (code: string) => perms().has(code),
    hasAnyPermission: (codes: readonly string[]) => codes.length === 0 || codes.some(c => perms().has(c)),
    hasRole: (role: string) => roleList().includes(role),
    refreshPermissions: async () => true,
    logout: () => undefined,
    /** Cambia los permisos como lo haría /auth/me tras un aviso de seguridad. */
    setPermissions: (codes: string[]) => perms.set(new Set(codes))
  };
  return fake;
}
