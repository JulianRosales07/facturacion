-- Roles por acceso a página para asignación directa y granular por usuario
INSERT INTO rol (nombre, descripcion, sistema) VALUES
    ('ACCESO_RESUMEN', 'Acceso a Resumen de recepción', TRUE),
    ('ACCESO_FACTURAS', 'Acceso a Facturas recibidas', TRUE),
    ('ACCESO_SINCRONIZACION', 'Acceso a Sincronización e historial', TRUE),
    ('ACCESO_IMPORTACION', 'Acceso a Importación de órdenes', TRUE),
    ('ACCESO_CARTERA', 'Acceso a Cartera', TRUE),
    ('ACCESO_PAGOS', 'Acceso a Pagos y retenciones', TRUE),
    ('ACCESO_AUDITORIA', 'Acceso a Auditoría de retenciones', TRUE),
    ('ACCESO_CATALOGO_TRIBUTARIO', 'Acceso a Catálogo tributario', TRUE),
    ('ACCESO_USUARIOS', 'Acceso a Gestión de usuarios', TRUE),
    ('ACCESO_ROLES', 'Acceso a Gestión de roles', TRUE),
    ('ACCESO_PERMISOS', 'Acceso a Catálogo de permisos', TRUE)
ON CONFLICT (nombre) DO NOTHING;

-- Asociar cada rol de página con sus permisos requeridos
INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id FROM rol r, permiso p
WHERE (r.nombre = 'ACCESO_RESUMEN' AND p.codigo IN ('INVOICES_VIEW'))
   OR (r.nombre = 'ACCESO_FACTURAS' AND p.codigo IN ('INVOICES_VIEW', 'INVOICES_CREATE', 'INVOICES_UPDATE', 'INVOICES_DELETE'))
   OR (r.nombre = 'ACCESO_SINCRONIZACION' AND p.codigo IN ('SYNC_VIEW', 'SYNC_EXECUTE'))
   OR (r.nombre = 'ACCESO_IMPORTACION' AND p.codigo IN ('IMPORTS_VIEW', 'IMPORTS_EXECUTE'))
   OR (r.nombre = 'ACCESO_CARTERA' AND p.codigo IN ('CARTERA_VIEW', 'CARTERA_SYNC'))
   OR (r.nombre = 'ACCESO_PAGOS' AND p.codigo IN ('PAYMENTS_VIEW', 'PAYMENTS_REGISTER'))
   OR (r.nombre = 'ACCESO_AUDITORIA' AND p.codigo IN ('WITHHOLDINGS_AUDIT_VIEW'))
   OR (r.nombre = 'ACCESO_CATALOGO_TRIBUTARIO' AND p.codigo IN ('TAX_CONCEPTS_VIEW', 'TAX_CONCEPTS_MANAGE'))
   OR (r.nombre = 'ACCESO_USUARIOS' AND p.codigo IN ('USERS_VIEW', 'USERS_CREATE', 'USERS_UPDATE', 'USERS_DELETE'))
   OR (r.nombre = 'ACCESO_ROLES' AND p.codigo IN ('ROLES_VIEW', 'ROLES_CREATE', 'ROLES_UPDATE', 'ROLES_DELETE', 'PERMISSIONS_ASSIGN'))
   OR (r.nombre = 'ACCESO_PERMISOS' AND p.codigo IN ('PERMISSIONS_VIEW', 'PERMISSIONS_MANAGE', 'PERMISSIONS_ASSIGN'))
ON CONFLICT DO NOTHING;
