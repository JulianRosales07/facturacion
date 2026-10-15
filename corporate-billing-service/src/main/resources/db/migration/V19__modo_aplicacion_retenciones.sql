-- ==============================================================================
-- Sprint 9 (HU-51): modo de aplicación de retenciones elegido por el cliente
-- ==============================================================================

-- 1. Cada pago conserva cómo se liquidaron sus retenciones. Todos los pagos anteriores se
--    liquidaron sobre el total de sus facturas, por eso se registran como CONSOLIDATED.
ALTER TABLE pago ADD COLUMN IF NOT EXISTS modo_aplicacion_retenciones VARCHAR(20) NOT NULL DEFAULT 'CONSOLIDATED';
ALTER TABLE pago ADD CONSTRAINT ck_pago_modo_aplicacion_retenciones
    CHECK (modo_aplicacion_retenciones IN ('PER_INVOICE', 'CONSOLIDATED'));

-- 2. En PER_INVOICE cada retención pertenece a una factura; en CONSOLIDATED queda asociada
--    al pago completo (factura_id NULL) y no se reparte artificialmente entre facturas.
ALTER TABLE retencion_pago ADD COLUMN IF NOT EXISTS factura_id UUID REFERENCES factura(id);
CREATE INDEX IF NOT EXISTS idx_retencion_pago_factura ON retencion_pago(factura_id);
