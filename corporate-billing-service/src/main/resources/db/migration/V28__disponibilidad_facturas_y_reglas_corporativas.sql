-- V28: Reglas de facturación por cliente y disponibilidad mensual de facturas
ALTER TABLE cliente_corporativo
    ADD COLUMN IF NOT EXISTS regla_facturacion VARCHAR(40) NOT NULL DEFAULT 'ORDINARIA',
    ADD COLUMN IF NOT EXISTS detalle_regla_corporativa VARCHAR(255);

ALTER TABLE factura
    ADD COLUMN IF NOT EXISTS disponible_para_pago_en TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS retraso_importacion_registrado BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN cliente_corporativo.regla_facturacion IS 'Regla de facturación del cliente: ORDINARIA (disponible día 1 o 2) o ESPECIAL_CORPORATIVA';
COMMENT ON COLUMN cliente_corporativo.detalle_regla_corporativa IS 'Detalles o condiciones contractuales específicas para clientes con regla especial';
COMMENT ON COLUMN factura.disponible_para_pago_en IS 'Fecha y hora en que la factura oficial quedó validada y disponible para pago';
COMMENT ON COLUMN factura.retraso_importacion_registrado IS 'Indica si la factura mensual ordinaria fue importada con retraso respecto al corte del día 2';
