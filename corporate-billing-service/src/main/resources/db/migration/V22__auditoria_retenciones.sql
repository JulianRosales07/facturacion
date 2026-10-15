-- Auditoría de porcentajes de retenciones.
--  * retencion_pago: qué quedó finalmente en el pago (snapshot), ahora con quién, cuándo y desde dónde.
--  * retencion_evento: qué hizo la persona durante el proceso (seleccionó, cambió, quitó...).
-- Horas: TIMESTAMPTZ con la hora del servidor (nunca la del navegador); se muestran en America/Bogota.

ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS porcentaje_ingresado  NUMERIC(9,6);
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS seleccionado_en       TIMESTAMPTZ;
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS aplicado_en           TIMESTAMPTZ;
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS canal_origen          VARCHAR(20);
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS seleccionado_por_usuario_id UUID;
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS seleccionado_por_usuario    VARCHAR(150);
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS seleccionado_por_nombre     VARCHAR(200);
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS seleccionado_por_nit        VARCHAR(30);
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS sesion_seleccion      VARCHAR(64);
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS ip_origen             VARCHAR(45);
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS agente_usuario        VARCHAR(500);
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS estado_seleccion      VARCHAR(20);

-- Histórico previo: se completa solo con lo que se sabe con certeza. El canal y el actor no se
-- registraban, así que quedan NULL ("no registrado") en lugar de inventarse.
UPDATE retencion_pago r SET
    porcentaje_ingresado = COALESCE(r.porcentaje_ingresado, r.porcentaje),
    seleccionado_en      = COALESCE(r.seleccionado_en, r.creado_en),
    estado_seleccion     = COALESCE(r.estado_seleccion, CASE
        WHEN NOT r.aplica THEN 'NOT_APPLICABLE'
        WHEN p.estado = 'APLICADO' THEN 'APPLIED'
        WHEN p.estado IN ('ANULADO', 'RECHAZADO') THEN 'CANCELLED'
        ELSE 'SELECTED' END),
    aplicado_en          = COALESCE(r.aplicado_en, CASE WHEN r.aplica AND p.estado = 'APLICADO'
        THEN COALESCE(p.confirmado_en, p.creado_en) END)
FROM pago p WHERE p.id = r.pago_id;

ALTER TABLE retencion_pago ALTER COLUMN porcentaje_ingresado SET NOT NULL;
ALTER TABLE retencion_pago ALTER COLUMN seleccionado_en SET NOT NULL;
ALTER TABLE retencion_pago ALTER COLUMN estado_seleccion SET NOT NULL;
ALTER TABLE retencion_pago ADD CONSTRAINT ck_retencion_pago_estado_seleccion
    CHECK (estado_seleccion IN ('SELECTED', 'NOT_APPLICABLE', 'APPLIED', 'CANCELLED'));
ALTER TABLE retencion_pago ADD CONSTRAINT ck_retencion_pago_canal
    CHECK (canal_origen IS NULL OR canal_origen IN ('PAYMENT_GATEWAY', 'ADMIN_WEB', 'MANUAL_PAYMENT', 'API'));

CREATE INDEX IF NOT EXISTS idx_retencion_pago_seleccionado_en ON retencion_pago (seleccionado_en DESC);
CREATE INDEX IF NOT EXISTS idx_retencion_pago_canal ON retencion_pago (canal_origen);
CREATE INDEX IF NOT EXISTS idx_retencion_pago_usuario ON retencion_pago (lower(seleccionado_por_usuario));
CREATE INDEX IF NOT EXISTS idx_retencion_pago_nit ON retencion_pago (seleccionado_por_nit);

-- Historial de interacción (no es el snapshot): una fila por acción, nunca se modifica.
-- pago_id no es llave foránea a propósito: la evidencia debe sobrevivir al pago.
CREATE TABLE IF NOT EXISTS retencion_evento (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sesion_seleccion     VARCHAR(64)  NOT NULL,
    pago_id              UUID,
    factura_id           UUID,
    nit_cliente          VARCHAR(30),
    vigencia_concepto_id UUID,
    concepto_codigo      VARCHAR(20)  NOT NULL,
    concepto_nombre      VARCHAR(100),
    tipo_evento          VARCHAR(20)  NOT NULL,
    porcentaje_anterior  NUMERIC(9,6),
    porcentaje_nuevo     NUMERIC(9,6),
    ocurrido_en          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    canal_origen         VARCHAR(20)  NOT NULL,
    actor_usuario_id     UUID,
    actor_usuario        VARCHAR(150),
    actor_nombre         VARCHAR(200),
    actor_nit            VARCHAR(30),
    ip_origen            VARCHAR(45),
    agente_usuario       VARCHAR(500),
    CONSTRAINT ck_retencion_evento_tipo CHECK (tipo_evento IN ('SELECTED', 'UPDATED', 'REMOVED', 'NOT_APPLICABLE', 'APPLIED', 'CANCELLED')),
    CONSTRAINT ck_retencion_evento_canal CHECK (canal_origen IN ('PAYMENT_GATEWAY', 'ADMIN_WEB', 'MANUAL_PAYMENT', 'API'))
);
CREATE INDEX IF NOT EXISTS idx_retencion_evento_sesion ON retencion_evento (sesion_seleccion, ocurrido_en);
CREATE INDEX IF NOT EXISTS idx_retencion_evento_pago ON retencion_evento (pago_id);

-- Inmutabilidad de la evidencia, también frente a SQL directo.
-- Snapshot: tras insertarse solo puede pasar de SELECTED a APPLIED/CANCELLED (con aplicado_en);
-- todo lo demás (porcentajes, bases, valores, vigencia, fechas, actor, canal) es inalterable.
CREATE OR REPLACE FUNCTION proteger_retencion_pago() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM pago WHERE id = OLD.pago_id AND estado = 'APLICADO') THEN
            RAISE EXCEPTION 'La retención % pertenece a un pago confirmado y no puede eliminarse', OLD.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF ROW(OLD.pago_id, OLD.vigencia_concepto_id, OLD.numero_version, OLD.concepto_codigo, OLD.nombre_concepto,
           OLD.porcentaje, OLD.porcentaje_ingresado, OLD.porcentaje_maximo, OLD.base_minima, OLD.base_calculo,
           OLD.valor_retenido, OLD.aplica, OLD.motivo_no_aplica, OLD.motivo_ajuste, OLD.vigente_desde, OLD.vigente_hasta,
           OLD.factura_id, OLD.seleccionado_en, OLD.canal_origen, OLD.seleccionado_por_usuario_id,
           OLD.seleccionado_por_usuario, OLD.seleccionado_por_nombre, OLD.seleccionado_por_nit, OLD.sesion_seleccion,
           OLD.ip_origen, OLD.agente_usuario)
       IS DISTINCT FROM
       ROW(NEW.pago_id, NEW.vigencia_concepto_id, NEW.numero_version, NEW.concepto_codigo, NEW.nombre_concepto,
           NEW.porcentaje, NEW.porcentaje_ingresado, NEW.porcentaje_maximo, NEW.base_minima, NEW.base_calculo,
           NEW.valor_retenido, NEW.aplica, NEW.motivo_no_aplica, NEW.motivo_ajuste, NEW.vigente_desde, NEW.vigente_hasta,
           NEW.factura_id, NEW.seleccionado_en, NEW.canal_origen, NEW.seleccionado_por_usuario_id,
           NEW.seleccionado_por_usuario, NEW.seleccionado_por_nombre, NEW.seleccionado_por_nit, NEW.sesion_seleccion,
           NEW.ip_origen, NEW.agente_usuario) THEN
        RAISE EXCEPTION 'El snapshot de la retención % es inmutable; registre un ajuste o reversión', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (OLD.estado_seleccion, OLD.aplicado_en) IS DISTINCT FROM (NEW.estado_seleccion, NEW.aplicado_en)
       AND OLD.estado_seleccion <> 'SELECTED' THEN
        RAISE EXCEPTION 'La retención % ya está % y no puede cambiar', OLD.id, OLD.estado_seleccion
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_proteger_retencion_pago ON retencion_pago;
CREATE TRIGGER trg_proteger_retencion_pago BEFORE UPDATE OR DELETE ON retencion_pago
    FOR EACH ROW EXECUTE FUNCTION proteger_retencion_pago();

CREATE OR REPLACE FUNCTION proteger_retencion_evento() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'El historial de retenciones es de solo inserción (evento %)', OLD.id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_proteger_retencion_evento ON retencion_evento;
CREATE TRIGGER trg_proteger_retencion_evento BEFORE UPDATE OR DELETE ON retencion_evento
    FOR EACH ROW EXECUTE FUNCTION proteger_retencion_evento();

-- Consulta de la auditoría: permiso propio (incluye IP y agente de usuario).
INSERT INTO permiso (codigo, nombre, descripcion, modulo, sistema) VALUES
    ('WITHHOLDINGS_AUDIT_VIEW', 'Ver auditoría de retenciones',
     'Consultar y exportar quién, cuándo y desde dónde diligenció cada retención', 'Pagos', TRUE)
ON CONFLICT (codigo) DO NOTHING;

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id FROM rol r JOIN permiso p ON p.codigo = 'WITHHOLDINGS_AUDIT_VIEW'
WHERE r.nombre IN ('SUPER_ADMIN', 'FINANCIERA')
ON CONFLICT DO NOTHING;
