-- Gestión de usuarios, roles y permisos.
-- La identidad (inicio de sesión y contraseñas) es del microservicio de autenticación OAuth2:
-- aquí no se guardan contraseñas. El usuario se vincula al token por nombre de usuario o correo y
-- sus permisos efectivos (unión de los permisos activos de sus roles activos) se cargan en cada petición.

CREATE TABLE IF NOT EXISTS usuario (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre_usuario   VARCHAR(100) NOT NULL,
    correo           VARCHAR(255) NOT NULL,
    nombres          VARCHAR(100) NOT NULL,
    apellidos        VARCHAR(100) NOT NULL,
    activo           BOOLEAN      NOT NULL DEFAULT TRUE,
    bloqueado        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Eliminación lógica: conserva la auditoría; el usuario deja de existir para el acceso.
    eliminado        BOOLEAN      NOT NULL DEFAULT FALSE,
    ultimo_acceso    TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0,
    creado_en        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    creado_por       VARCHAR(150),
    actualizado_por  VARCHAR(150)
);
-- Únicos sin distinguir mayúsculas: "JPerez" y "jperez" son el mismo usuario.
CREATE UNIQUE INDEX IF NOT EXISTS ux_usuario_nombre_usuario ON usuario (lower(nombre_usuario));
CREATE UNIQUE INDEX IF NOT EXISTS ux_usuario_correo ON usuario (lower(correo));
CREATE INDEX IF NOT EXISTS ix_usuario_activo ON usuario (activo) WHERE NOT eliminado;

CREATE TABLE IF NOT EXISTS rol (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre          VARCHAR(60)  NOT NULL,
    descripcion     VARCHAR(255),
    activo          BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Rol interno: no se puede eliminar ni renombrar.
    sistema         BOOLEAN      NOT NULL DEFAULT FALSE,
    version         BIGINT       NOT NULL DEFAULT 0,
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_rol_nombre UNIQUE (nombre)
);

CREATE TABLE IF NOT EXISTS permiso (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo          VARCHAR(80)  NOT NULL,
    nombre          VARCHAR(120) NOT NULL,
    descripcion     VARCHAR(255),
    modulo          VARCHAR(60)  NOT NULL,
    activo          BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Permiso que usa el código (@PreAuthorize): su código no cambia y no se puede eliminar.
    sistema         BOOLEAN      NOT NULL DEFAULT FALSE,
    version         BIGINT       NOT NULL DEFAULT 0,
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_permiso_codigo UNIQUE (codigo)
);
CREATE INDEX IF NOT EXISTS ix_permiso_modulo ON permiso (modulo);

CREATE TABLE IF NOT EXISTS usuario_rol (
    usuario_id UUID NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    rol_id     UUID NOT NULL REFERENCES rol (id) ON DELETE CASCADE,
    CONSTRAINT pk_usuario_rol PRIMARY KEY (usuario_id, rol_id)
);
CREATE INDEX IF NOT EXISTS ix_usuario_rol_rol ON usuario_rol (rol_id);

CREATE TABLE IF NOT EXISTS rol_permiso (
    rol_id     UUID NOT NULL REFERENCES rol (id) ON DELETE CASCADE,
    permiso_id UUID NOT NULL REFERENCES permiso (id) ON DELETE CASCADE,
    CONSTRAINT pk_rol_permiso PRIMARY KEY (rol_id, permiso_id)
);
CREATE INDEX IF NOT EXISTS ix_rol_permiso_permiso ON rol_permiso (permiso_id);

CREATE TABLE IF NOT EXISTS auditoria_acceso (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor       VARCHAR(150) NOT NULL,
    accion      VARCHAR(60)  NOT NULL,
    entidad     VARCHAR(30)  NOT NULL,
    entidad_id  UUID,
    detalle     TEXT,
    ocurrido_en TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_auditoria_acceso_entidad ON auditoria_acceso (entidad, entidad_id);
CREATE INDEX IF NOT EXISTS ix_auditoria_acceso_fecha ON auditoria_acceso (ocurrido_en DESC);

-- Catálogo de permisos que exige el backend.
INSERT INTO permiso (codigo, nombre, descripcion, modulo, sistema) VALUES
    ('USERS_VIEW',          'Ver usuarios',                 'Consultar usuarios y sus roles',                         'Administración', TRUE),
    ('USERS_CREATE',        'Crear usuarios',               'Registrar usuarios nuevos',                              'Administración', TRUE),
    ('USERS_UPDATE',        'Editar usuarios',              'Modificar datos, estado y roles de usuarios',            'Administración', TRUE),
    ('USERS_DELETE',        'Eliminar usuarios',            'Eliminar usuarios (eliminación lógica)',                 'Administración', TRUE),
    ('ROLES_VIEW',          'Ver roles',                    'Consultar roles y sus permisos',                         'Administración', TRUE),
    ('ROLES_CREATE',        'Crear roles',                  'Registrar roles nuevos',                                 'Administración', TRUE),
    ('ROLES_UPDATE',        'Editar roles',                 'Modificar nombre, descripción y estado de roles',        'Administración', TRUE),
    ('ROLES_DELETE',        'Eliminar roles',               'Eliminar roles que no son del sistema',                  'Administración', TRUE),
    ('PERMISSIONS_VIEW',    'Ver permisos',                 'Consultar el catálogo de permisos',                      'Administración', TRUE),
    ('PERMISSIONS_MANAGE',  'Administrar permisos',         'Crear, editar y eliminar permisos del catálogo',         'Administración', TRUE),
    ('PERMISSIONS_ASSIGN',  'Asignar permisos',             'Asignar permisos a los roles',                           'Administración', TRUE),
    ('INVOICES_VIEW',       'Ver facturas',                 'Consultar facturas, versiones, PDF y notas crédito',     'Facturación',    TRUE),
    ('INVOICES_CREATE',     'Generar facturas',             'Generar facturas del periodo',                           'Facturación',    TRUE),
    ('INVOICES_UPDATE',     'Regenerar facturas',           'Regenerar facturas y emitir notas crédito',              'Facturación',    TRUE),
    ('INVOICES_DELETE',     'Anular facturas',              'Anular facturas',                                        'Facturación',    TRUE),
    ('PAYMENTS_VIEW',       'Ver pagos',                    'Consultar pagos, recibos y retenciones aplicadas',       'Pagos',          TRUE),
    ('PAYMENTS_REGISTER',   'Registrar pagos',              'Simular y registrar pagos con retenciones',              'Pagos',          TRUE),
    ('CARTERA_VIEW',        'Ver cartera',                  'Consultar cartera y conciliación',                       'Cartera',        TRUE),
    ('CARTERA_SYNC',        'Sincronizar cartera',          'Sincronizar la cartera desde Siigo',                     'Cartera',        TRUE),
    ('CLIENTS_VIEW',        'Ver clientes',                 'Consultar el directorio de clientes',                    'Clientes',       TRUE),
    ('TAX_CONCEPTS_VIEW',   'Ver catálogo tributario',      'Consultar conceptos de retención y vigencias',           'Tributos',       TRUE),
    ('TAX_CONCEPTS_MANAGE', 'Administrar catálogo tributario', 'Crear, editar y desactivar conceptos de retención',  'Tributos',       TRUE),
    ('REPORTS_VIEW',        'Ver reportes',                 'Consultar reportes',                                     'Reportes',       TRUE),
    ('IMPORTS_VIEW',        'Ver importaciones',            'Consultar historial y configuración de importaciones',   'Integración',    TRUE),
    ('IMPORTS_EXECUTE',     'Importar archivos',            'Importar órdenes, novedades y facturas de Siigo',        'Integración',    TRUE),
    ('SYNC_VIEW',           'Ver sincronización',           'Consultar el estado de la sincronización',               'Integración',    TRUE),
    ('SYNC_EXECUTE',        'Ejecutar sincronización',      'Lanzar sincronizaciones manuales',                       'Integración',    TRUE),
    ('BATCHES_VIEW',        'Ver lotes',                    'Consultar lotes de exportación a Siigo',                 'Integración',    TRUE),
    ('BATCHES_MANAGE',      'Gestionar lotes',              'Generar y confirmar lotes de exportación a Siigo',       'Integración',    TRUE)
ON CONFLICT (codigo) DO NOTHING;

INSERT INTO rol (nombre, descripcion, sistema) VALUES
    ('SUPER_ADMIN', 'Acceso total al sistema',                                        TRUE),
    ('FINANCIERA',  'Facturación, pagos, cartera y tributos; sin gestión de usuarios', TRUE),
    ('SOPORTE',     'Solo gestión de usuarios, roles y permisos',                     TRUE)
ON CONFLICT (nombre) DO NOTHING;

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id FROM rol r CROSS JOIN permiso p
WHERE r.nombre = 'SUPER_ADMIN'
   OR (r.nombre = 'FINANCIERA' AND p.modulo <> 'Administración')
   OR (r.nombre = 'SOPORTE' AND p.modulo = 'Administración')
ON CONFLICT DO NOTHING;
