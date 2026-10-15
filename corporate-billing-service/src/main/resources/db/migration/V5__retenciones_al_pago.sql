-- ==============================================================================
-- Sprint 9: Migración V5 - Retenciones al pago y clasificación residencial (HU-51, HU-60)
-- ==============================================================================

-- 1. Clasificación residencial (HU-60) para separar residenciales masivas de corporativos
ALTER TABLE factura ADD COLUMN IF NOT EXISTS es_residencial BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sede_suscripcion ADD COLUMN IF NOT EXISTS es_residencial BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Asegurar que las columnas de retención en factura permitan 0 o nulo por omisión
ALTER TABLE factura ALTER COLUMN valor_retefuente SET DEFAULT 0;
ALTER TABLE factura ALTER COLUMN valor_reteica SET DEFAULT 0;
ALTER TABLE factura ALTER COLUMN valor_reteiva SET DEFAULT 0;
ALTER TABLE factura ALTER COLUMN valor_estampillas SET DEFAULT 0;
ALTER TABLE factura ALTER COLUMN valor_total_retenciones SET DEFAULT 0;
ALTER TABLE factura ALTER COLUMN neto_a_pagar SET DEFAULT 0;
