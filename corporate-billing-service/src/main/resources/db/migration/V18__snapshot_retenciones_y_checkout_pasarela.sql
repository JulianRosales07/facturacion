-- ==============================================================================
-- Sprint 9 (HU-51, HU-52, HU-54): snapshot completo de retenciones y checkout de pasarela
-- ==============================================================================

-- 1. El snapshot conserva también los conceptos seleccionados que NO aplicaron (base mínima),
--    y la vigencia exacta del concepto con la que se liquidó. Los registros históricos
--    existentes solo guardaban retenciones aplicadas, por eso aplica = TRUE por omisión.
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS aplica BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS motivo_no_aplica VARCHAR(255);
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS vigente_desde DATE;
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS vigente_hasta DATE;

-- 2. Un pago iniciado en la pasarela nace PENDIENTE y solo se APLICA con la confirmación.
--    confirmado_en registra ese instante; los pagos existentes ya estaban aplicados al crearse.
ALTER TABLE pago ADD COLUMN IF NOT EXISTS confirmado_en TIMESTAMPTZ;
UPDATE pago SET confirmado_en = creado_en WHERE estado = 'APLICADO' AND confirmado_en IS NULL;

CREATE INDEX IF NOT EXISTS idx_pago_estado ON pago(estado);
