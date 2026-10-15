-- ==============================================================================
-- Sprint 9 (HU-51): el porcentaje de retención lo ingresa el cliente al pagar
-- ==============================================================================

-- 1. El catálogo deja de fijar la tarifa: cada concepto define el porcentaje MÁXIMO que el
--    cliente puede practicar. Se inicializa con la tarifa que tenía cada vigencia; el
--    administrador debe ajustarlo desde el panel. La columna "porcentaje" queda sin uso.
ALTER TABLE vigencia_concepto ADD COLUMN IF NOT EXISTS porcentaje_maximo NUMERIC(7,4);
UPDATE vigencia_concepto SET porcentaje_maximo = porcentaje WHERE porcentaje_maximo IS NULL;
ALTER TABLE vigencia_concepto ALTER COLUMN porcentaje_maximo SET NOT NULL;
ALTER TABLE vigencia_concepto ALTER COLUMN porcentaje DROP NOT NULL;
COMMENT ON COLUMN vigencia_concepto.porcentaje IS 'Obsoleta desde V20: la tarifa la ingresa el cliente; ver porcentaje_maximo';
COMMENT ON COLUMN vigencia_concepto.porcentaje_maximo IS 'Porcentaje máximo que el cliente puede practicar con este concepto';

-- 2. El snapshot del pago conserva, junto al porcentaje aplicado (porcentaje), el máximo que
--    estaba permitido al liquidar. NULL en pagos anteriores a este cambio.
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS porcentaje_maximo NUMERIC(7,4);

-- 3. Configuración de retenciones propia de cada cliente, guardada solo con su autorización
--    expresa para precargarla en pagos futuros. Un cliente tiene a lo sumo una configuración.
CREATE TABLE IF NOT EXISTS configuracion_retencion_cliente (
    id                     UUID PRIMARY KEY,
    cliente_corporativo_id UUID NOT NULL UNIQUE REFERENCES cliente_corporativo(id),
    modo_aplicacion        VARCHAR(20) NOT NULL CHECK (modo_aplicacion IN ('PER_INVOICE', 'CONSOLIDATED')),
    autorizado_en          TIMESTAMPTZ NOT NULL,
    autorizado_por         VARCHAR(150),
    actualizado_en         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS configuracion_retencion_cliente_item (
    id                   UUID PRIMARY KEY,
    configuracion_id     UUID NOT NULL REFERENCES configuracion_retencion_cliente(id) ON DELETE CASCADE,
    vigencia_concepto_id UUID NOT NULL REFERENCES vigencia_concepto(id),
    porcentaje           NUMERIC(7,4) NOT NULL CHECK (porcentaje > 0 AND porcentaje <= 100),
    UNIQUE (configuracion_id, vigencia_concepto_id)
);
