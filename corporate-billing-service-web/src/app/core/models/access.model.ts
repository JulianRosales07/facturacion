/** Usuario autenticado según el backend (GET /api/v1/auth/me). */
export interface CurrentUserAccess {
  id: string | null;
  username: string;
  name: string | null;
  email: string | null;
  /** false: la identidad del SSO no está registrada (o está deshabilitada) en este sistema. */
  registered: boolean;
  roles: string[];
  permissions: string[];
}

export interface RoleRef { id: string; name: string; active: boolean; }
export interface PermissionRef { id: string; code: string; name: string; module: string; }
export interface UserRef { id: string; username: string; fullName: string; active: boolean; }

export interface AdminUser {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  fullName: string;
  active: boolean;
  locked: boolean;
  lastLoginAt: string | null;
  roles: RoleRef[];
  permissions?: string[];
  version: number | null;
  createdAt: string;
  updatedAt: string;
  createdBy?: string;
  updatedBy?: string;
}

export interface AdminRole {
  id: string;
  name: string;
  description: string | null;
  active: boolean;
  systemRole: boolean;
  userCount: number;
  permissionCount: number;
  permissions?: PermissionRef[];
  users?: UserRef[];
  version: number | null;
}

export interface AdminPermission {
  id: string;
  code: string;
  name: string;
  description: string | null;
  module: string;
  active: boolean;
  systemPermission: boolean;
  roleCount: number;
  roles?: RoleRef[];
  version: number | null;
}

export interface PageResponse<T> { items: T[]; page: number; size: number; totalItems: number; totalPages: number; }

export interface UserFilter { username?: string; name?: string; email?: string; active?: boolean | null; role?: string; }

export interface UserPayload {
  username?: string;
  email: string;
  firstName: string;
  lastName: string;
  active: boolean;
  roleIds: string[];
  version?: number | null;
}

export interface RolePayload { name: string; description: string | null; active: boolean; permissionIds?: string[]; version?: number | null; }

export interface PermissionPayload { code: string; name: string; description: string | null; module: string; active: boolean; version?: number | null; }

/** Permisos que exige el backend (catálogo de la migración V21). */
export const PERMISSIONS = {
  USERS_VIEW: 'USERS_VIEW', USERS_CREATE: 'USERS_CREATE', USERS_UPDATE: 'USERS_UPDATE', USERS_DELETE: 'USERS_DELETE',
  ROLES_VIEW: 'ROLES_VIEW', ROLES_CREATE: 'ROLES_CREATE', ROLES_UPDATE: 'ROLES_UPDATE', ROLES_DELETE: 'ROLES_DELETE',
  PERMISSIONS_VIEW: 'PERMISSIONS_VIEW', PERMISSIONS_MANAGE: 'PERMISSIONS_MANAGE', PERMISSIONS_ASSIGN: 'PERMISSIONS_ASSIGN',
  INVOICES_VIEW: 'INVOICES_VIEW', PAYMENTS_VIEW: 'PAYMENTS_VIEW', PAYMENTS_REGISTER: 'PAYMENTS_REGISTER',
  CARTERA_VIEW: 'CARTERA_VIEW', TAX_CONCEPTS_VIEW: 'TAX_CONCEPTS_VIEW', TAX_CONCEPTS_MANAGE: 'TAX_CONCEPTS_MANAGE',
  IMPORTS_VIEW: 'IMPORTS_VIEW', SYNC_VIEW: 'SYNC_VIEW'
} as const;

/** Capacidad mínima para administrar accesos; el backend impide que el sistema se quede sin ella. */
export const ACCESS_ADMIN_PERMISSIONS = [PERMISSIONS.USERS_UPDATE, PERMISSIONS.ROLES_UPDATE];

/** Catálogo completo (vista local sin backend: el administrador local tiene todos). */
export const ALL_PERMISSION_CODES = [
  'USERS_VIEW', 'USERS_CREATE', 'USERS_UPDATE', 'USERS_DELETE', 'ROLES_VIEW', 'ROLES_CREATE', 'ROLES_UPDATE', 'ROLES_DELETE',
  'PERMISSIONS_VIEW', 'PERMISSIONS_MANAGE', 'PERMISSIONS_ASSIGN', 'INVOICES_VIEW', 'INVOICES_CREATE', 'INVOICES_UPDATE',
  'INVOICES_DELETE', 'PAYMENTS_VIEW', 'PAYMENTS_REGISTER', 'WITHHOLDINGS_AUDIT_VIEW', 'CARTERA_VIEW', 'CARTERA_SYNC', 'CLIENTS_VIEW', 'TAX_CONCEPTS_VIEW',
  'TAX_CONCEPTS_MANAGE', 'REPORTS_VIEW', 'IMPORTS_VIEW', 'IMPORTS_EXECUTE', 'SYNC_VIEW', 'SYNC_EXECUTE', 'BATCHES_VIEW', 'BATCHES_MANAGE'
];

/** 'SUPER_ADMIN' → 'Super admin'. */
export function roleLabel(role: string): string {
  const text = role.replace(/_/g, ' ').toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}
