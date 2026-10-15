-- Ciclo de vida de cada novedad frente a la facturación (auditoría e idempotencia).
--   PENDIENTE         registrada; entra cuando se genere o recalcule la factura de su periodo
--   APLICADA          incorporada a la factura de su periodo (factura_id)
--   PENDIENTE_AJUSTE  su periodo ya estaba emitido o pagado: el impacto (valor_impacto) se
--                     cobra o compensa en la siguiente factura en preparación del cliente
--   AJUSTE_APLICADO   ese ajuste ya entró en otra factura (factura_id)
ALTER TABLE novedad_orden
    ADD COLUMN estado_procesamiento VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE'
        CHECK (estado_procesamiento IN ('PENDIENTE', 'APLICADA', 'PENDIENTE_AJUSTE', 'AJUSTE_APLICADO')),
    ADD COLUMN valor_impacto NUMERIC(14, 2),
    ADD COLUMN detalle_procesamiento VARCHAR(255),
    ADD COLUMN procesado_en TIMESTAMPTZ;

-- Las novedades ya facturadas conservan su estado real.
UPDATE novedad_orden
   SET estado_procesamiento = 'APLICADA', procesado_en = facturado_en
 WHERE factura_id IS NOT NULL;

CREATE INDEX ix_novedad_orden_ajustes_pendientes ON novedad_orden(estado_procesamiento)
    WHERE estado_procesamiento = 'PENDIENTE_AJUSTE';
