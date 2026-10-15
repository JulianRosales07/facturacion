-- Mantiene la unicidad de la generación interna, admite documentos oficiales
-- independientes del mismo cliente y periodo. No modifica migraciones aplicadas.
ALTER TABLE factura ADD COLUMN origen_documento VARCHAR(20) NOT NULL DEFAULT 'INTERNO';
ALTER TABLE factura ADD COLUMN clave_documento_externo VARCHAR(80);
CREATE UNIQUE INDEX uq_factura_documento_externo ON factura(clave_documento_externo)
    WHERE clave_documento_externo IS NOT NULL;
DROP INDEX IF EXISTS uq_factura_cliente_periodo_activa;
CREATE UNIQUE INDEX uq_factura_cliente_periodo_activa
    ON factura(cliente_corporativo_id, periodo_facturacion_id)
    WHERE estado NOT IN ('ANULADA', 'REEMPLAZADA') AND origen_documento = 'INTERNO';

ALTER TABLE pago ADD COLUMN clave_idempotencia VARCHAR(128);
ALTER TABLE pago ADD COLUMN huella_solicitud VARCHAR(64);
CREATE UNIQUE INDEX uq_pago_idempotencia ON pago(clave_idempotencia)
    WHERE clave_idempotencia IS NOT NULL;
-- Los pagos históricos permanecen intactos; los nuevos deben tener referencia.
CREATE UNIQUE INDEX uq_pago_transaccion_nueva ON pago(medio_pago, cuenta_banco, numero_transaccion)
    WHERE clave_idempotencia IS NOT NULL;
ALTER TABLE retencion_pago ALTER COLUMN porcentaje TYPE NUMERIC(9,6);
