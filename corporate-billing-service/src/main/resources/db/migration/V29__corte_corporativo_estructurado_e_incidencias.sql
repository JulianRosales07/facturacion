-- V29: Día de corte corporativo estructurado e incidencias persistentes de facturación
ALTER TABLE cliente_corporativo
    ADD COLUMN IF NOT EXISTS dia_corte_corporativo INT;

ALTER TABLE cliente_corporativo
    ADD CONSTRAINT chk_dia_corte_corporativo
    CHECK (dia_corte_corporativo IS NULL OR (dia_corte_corporativo >= 1 AND dia_corte_corporativo <= 28));

COMMENT ON COLUMN cliente_corporativo.dia_corte_corporativo IS 'Día del mes (1 a 28) en que se vence el plazo contractual para recibir la factura oficial Siigo';

CREATE TABLE IF NOT EXISTS incidencia_facturacion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_id UUID NOT NULL REFERENCES cliente_corporativo(id) ON DELETE CASCADE,
    nit VARCHAR(20) NOT NULL,
    razon_social VARCHAR(255) NOT NULL,
    periodo VARCHAR(10) NOT NULL,
    tipo_incidencia VARCHAR(50) NOT NULL,
    estado_atencion VARCHAR(30) NOT NULL DEFAULT 'ABIERTA',
    descripcion TEXT NOT NULL,
    dia_corte_esperado INT,
    fecha_corte_esperada DATE,
    fecha_deteccion TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fecha_resolucion TIMESTAMPTZ,
    observaciones_resolucion TEXT,
    responsable_asignado VARCHAR(150),
    observaciones_seguimiento TEXT,
    notificacion_enviada BOOLEAN NOT NULL DEFAULT FALSE,
    canal_notificacion VARCHAR(50),
    fecha_notificacion TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_incidencia_cliente_periodo_tipo UNIQUE (cliente_id, periodo, tipo_incidencia)
);

CREATE INDEX IF NOT EXISTS idx_incidencia_estado ON incidencia_facturacion(estado_atencion);
CREATE INDEX IF NOT EXISTS idx_incidencia_periodo ON incidencia_facturacion(periodo);
CREATE INDEX IF NOT EXISTS idx_incidencia_cliente ON incidencia_facturacion(cliente_id);

COMMENT ON TABLE incidencia_facturacion IS 'Registro auditable de incidencias de facturas faltantes y configuraciones incompletas';

INSERT INTO permiso (codigo, nombre, descripcion, modulo, sistema) VALUES
    ('BILLING_RULES_VIEW', 'Ver reglas de facturación', 'Consultar reglas de corte y calendario de facturación', 'Facturación', TRUE),
    ('BILLING_RULES_MANAGE', 'Gestionar reglas de facturación', 'Modificar cortes y condiciones especiales corporativas', 'Facturación', TRUE),
    ('BILLING_INCIDENTS_VIEW', 'Ver incidencias de facturación', 'Consultar alertas y facturas faltantes', 'Facturación', TRUE),
    ('BILLING_INCIDENTS_MANAGE', 'Gestionar incidencias', 'Resolver y asignar incidencias de facturación', 'Facturación', TRUE)
ON CONFLICT (codigo) DO NOTHING;

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_ADMIN', 'FINANCIERA')
  AND p.codigo IN ('BILLING_RULES_VIEW', 'BILLING_RULES_MANAGE', 'BILLING_INCIDENTS_VIEW', 'BILLING_INCIDENTS_MANAGE')
ON CONFLICT DO NOTHING;
