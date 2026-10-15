-- ==============================================================================
-- Sprint 9: Migración V6 - Módulo de Recaudo (HU-53, HU-54, HU-55)
-- Tablas: pago, pago_factura, retencion_pago
-- ==============================================================================

-- 1. Tabla principal de Pagos
CREATE TABLE IF NOT EXISTS pago (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_corporativo_id UUID NOT NULL REFERENCES cliente_corporativo(id),
    fecha_pago             DATE NOT NULL,
    medio_pago             VARCHAR(50) NOT NULL,
    cuenta_banco           VARCHAR(50) NOT NULL,
    valor_bruto            NUMERIC(14,2) NOT NULL,
    total_retenciones      NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_redondeo         NUMERIC(14,2) NOT NULL DEFAULT 0,
    neto_pagado            NUMERIC(14,2) NOT NULL,
    numero_transaccion     VARCHAR(100),
    observacion            VARCHAR(255),
    usuario_creacion       VARCHAR(100),
    estado                 VARCHAR(20) NOT NULL DEFAULT 'APLICADO',
    creado_en              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pago_cliente ON pago(cliente_corporativo_id);
CREATE INDEX IF NOT EXISTS idx_pago_fecha ON pago(fecha_pago);

-- 2. Cruce Pago <-> Facturas
-- Reemplazamos la tabla pago_factura preliminar de V1 por la estructura de cruce de recaudo
DROP TABLE IF EXISTS pago_factura CASCADE;

CREATE TABLE pago_factura (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pago_id        UUID NOT NULL REFERENCES pago(id) ON DELETE CASCADE,
    factura_id     UUID NOT NULL REFERENCES factura(id),
    valor_aplicado NUMERIC(14,2) NOT NULL,
    creado_en      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pago_factura_pago ON pago_factura(pago_id);
CREATE INDEX IF NOT EXISTS idx_pago_factura_factura ON pago_factura(factura_id);

-- 3. Histórico inmutable de retenciones aplicadas en cada pago
CREATE TABLE IF NOT EXISTS retencion_pago (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pago_id              UUID NOT NULL REFERENCES pago(id) ON DELETE CASCADE,
    vigencia_concepto_id UUID REFERENCES vigencia_concepto(id),
    numero_version       INT NOT NULL DEFAULT 1,
    concepto_codigo      VARCHAR(20) NOT NULL,
    nombre_concepto      VARCHAR(100) NOT NULL,
    porcentaje           NUMERIC(5,2) NOT NULL,
    base_minima          NUMERIC(14,2) NOT NULL,
    base_calculo         NUMERIC(14,2) NOT NULL,
    valor_retenido       NUMERIC(14,2) NOT NULL,
    creado_en            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_retencion_pago_pago ON retencion_pago(pago_id);
