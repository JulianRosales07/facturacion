import { AdminRole, AdminUser } from './access.model';

export interface PageAccessItem {
  key: string;
  name: string;
  route: string;
  module: string;
  icon: string;
  description: string;
  roleName: string;
  permissionCodes: string[];
}

export const PAGE_ACCESS_ITEMS: PageAccessItem[] = [
  {
    key: 'dashboard',
    name: 'Resumen de recepción',
    route: '/dashboard',
    module: 'Operación diaria',
    icon: 'grid',
    description: 'Métricas, conteos y resumen ejecutivo de facturas recibidas',
    roleName: 'ACCESO_RESUMEN',
    permissionCodes: ['INVOICES_VIEW']
  },
  {
    key: 'invoices',
    name: 'Facturas recibidas',
    route: '/consultar-facturas',
    module: 'Operación diaria',
    icon: 'file-text',
    description: 'Consulta, detalle, notas crédito y descarga de facturas oficiales',
    roleName: 'ACCESO_FACTURAS',
    permissionCodes: ['INVOICES_VIEW', 'INVOICES_CREATE', 'INVOICES_UPDATE', 'INVOICES_DELETE']
  },
  {
    key: 'imports',
    name: 'Importación de órdenes',
    route: '/integracion-ordenes',
    module: 'Operación diaria',
    icon: 'upload',
    description: 'Carga masiva de suscripciones, novedades contractuales y consumo de carpetas',
    roleName: 'ACCESO_IMPORTACION',
    permissionCodes: ['IMPORTS_VIEW', 'IMPORTS_EXECUTE']
  },
  {
    key: 'sync',
    name: 'Sincronización e historial',
    route: '/sincronizacion',
    module: 'Operación diaria',
    icon: 'sync',
    description: 'Lectura de carpeta del servidor Siigo e historial de procesamiento',
    roleName: 'ACCESO_SINCRONIZACION',
    permissionCodes: ['SYNC_VIEW', 'SYNC_EXECUTE']
  },
  {
    key: 'cartera',
    name: 'Cartera',
    route: '/cartera',
    module: 'Cartera y tributos',
    icon: 'wallet',
    description: 'Consulta de saldos pendientes, estados de cuenta y conciliación',
    roleName: 'ACCESO_CARTERA',
    permissionCodes: ['CARTERA_VIEW', 'CARTERA_SYNC']
  },
  {
    key: 'payments',
    name: 'Pagos y retenciones',
    route: '/pagos',
    module: 'Cartera y tributos',
    icon: 'dollar',
    description: 'Simulación, liquidación y registro de pagos con retenciones en la fuente',
    roleName: 'ACCESO_PAGOS',
    permissionCodes: ['PAYMENTS_VIEW', 'PAYMENTS_REGISTER']
  },
  {
    key: 'withholdings',
    name: 'Auditoría de retenciones',
    route: '/auditoria-retenciones',
    module: 'Cartera y tributos',
    icon: 'fact_check',
    description: 'Evidencia histórica de retenciones aplicadas, porcentajes, canal y usuario',
    roleName: 'ACCESO_AUDITORIA',
    permissionCodes: ['WITHHOLDINGS_AUDIT_VIEW']
  },
  {
    key: 'taxes',
    name: 'Catálogo tributario',
    route: '/catalogo-tributario',
    module: 'Cartera y tributos',
    icon: 'activity',
    description: 'Catálogo oficial de conceptos de retención, tarifas y vigencias',
    roleName: 'ACCESO_CATALOGO_TRIBUTARIO',
    permissionCodes: ['TAX_CONCEPTS_VIEW', 'TAX_CONCEPTS_MANAGE']
  },
  {
    key: 'billing-rules',
    name: 'Reglas e incidencias',
    route: '/admin/billing-rules',
    module: 'Administración',
    icon: 'rule',
    description: 'Ciclos de facturación (mes en curso / vencido), disponibilidad y resolución de incidencias',
    roleName: 'ACCESO_REGLAS_FACTURACION',
    permissionCodes: ['BILLING_RULES_VIEW', 'BILLING_RULES_MANAGE', 'BILLING_INCIDENTS_VIEW', 'BILLING_INCIDENTS_MANAGE']
  },
  {
    key: 'users',
    name: 'Usuarios',
    route: '/admin/users',
    module: 'Administración',
    icon: 'users',
    description: 'Gestión de cuentas, estado y asignación de accesos a páginas',
    roleName: 'ACCESO_USUARIOS',
    permissionCodes: ['USERS_VIEW', 'USERS_CREATE', 'USERS_UPDATE', 'USERS_DELETE']
  },
  {
    key: 'roles',
    name: 'Roles',
    route: '/admin/roles',
    module: 'Administración',
    icon: 'admin_panel_settings',
    description: 'Perfiles agrupados de roles y seguridad del sistema',
    roleName: 'ACCESO_ROLES',
    permissionCodes: ['ROLES_VIEW', 'ROLES_CREATE', 'ROLES_UPDATE', 'ROLES_DELETE']
  },
  {
    key: 'permissions',
    name: 'Permisos',
    route: '/admin/permissions',
    module: 'Administración',
    icon: 'vpn_key',
    description: 'Catálogo de accesos a las pantallas y módulos del sistema',
    roleName: 'ACCESO_PERMISOS',
    permissionCodes: ['PERMISSIONS_VIEW', 'PERMISSIONS_MANAGE', 'PERMISSIONS_ASSIGN']
  }
];

export const PAGE_MODULES = [
  'Operación diaria',
  'Cartera y tributos',
  'Administración'
];

/**
 * Determina qué páginas del sistema tiene habilitadas un usuario.
 */
export function getUserAccessiblePages(user: AdminUser): PageAccessItem[] {
  const roleNames = new Set((user.roles || []).map(r => r.name.toUpperCase()));
  const effectivePerms = new Set(user.permissions || []);

  if (roleNames.has('SUPER_ADMIN')) {
    return [...PAGE_ACCESS_ITEMS];
  }

  return PAGE_ACCESS_ITEMS.filter(item => {
    // Coincidencia por rol de página o roles del sistema
    if (roleNames.has(item.roleName)) return true;
    if (roleNames.has('FINANCIERA') && item.module !== 'Administración') return true;
    if (roleNames.has('SOPORTE') && item.module === 'Administración') return true;

    // Coincidencia por permisos efectivos cargados en el usuario
    if (effectivePerms.size > 0 && item.permissionCodes.some(p => effectivePerms.has(p))) {
      return true;
    }

    return false;
  });
}

/**
 * Traduce un conjunto de claves de página seleccionadas a los IDs de roles a asignar al usuario.
 */
export function resolveRoleIdsForPageKeys(
  selectedKeys: Set<string>,
  availableRoles: AdminRole[]
): string[] {
  const resultRoleIds = new Set<string>();
  const roleMapByName = new Map(availableRoles.map(r => [r.name.toUpperCase(), r.id]));

  // Si tiene todas las páginas seleccionadas y existe SUPER_ADMIN
  if (selectedKeys.size === PAGE_ACCESS_ITEMS.length && roleMapByName.has('SUPER_ADMIN')) {
    resultRoleIds.add(roleMapByName.get('SUPER_ADMIN')!);
    return Array.from(resultRoleIds);
  }

  // Verificar si tiene todas las de Financiera
  const financieraPages = PAGE_ACCESS_ITEMS.filter(p => p.module !== 'Administración').map(p => p.key);
  const adminPages = PAGE_ACCESS_ITEMS.filter(p => p.module === 'Administración').map(p => p.key);

  const hasAllFinanciera = financieraPages.every(k => selectedKeys.has(k));
  const hasAllAdmin = adminPages.every(k => selectedKeys.has(k));

  if (hasAllFinanciera && roleMapByName.has('FINANCIERA')) {
    resultRoleIds.add(roleMapByName.get('FINANCIERA')!);
  }
  if (hasAllAdmin && roleMapByName.has('SOPORTE')) {
    resultRoleIds.add(roleMapByName.get('SOPORTE')!);
  }

  // Para cada página seleccionada, añadir su rol de página específico si existe
  for (const item of PAGE_ACCESS_ITEMS) {
    if (selectedKeys.has(item.key)) {
      const specificId = roleMapByName.get(item.roleName);
      if (specificId) {
        resultRoleIds.add(specificId);
      }
    }
  }

  return Array.from(resultRoleIds);
}
