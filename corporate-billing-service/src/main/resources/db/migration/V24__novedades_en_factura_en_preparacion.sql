-- Novedades acumuladas en la factura en preparación del cliente y periodo.

-- RN-03: cada concepto facturado conserva la novedad que lo originó. NULL para el
-- servicio original del mes y para las líneas anteriores a esta migración. Si la
-- novedad se elimina, la línea facturada se conserva sin referencia.
ALTER TABLE item_facturable
    ADD COLUMN novedad_orden_id UUID REFERENCES novedad_orden(id) ON DELETE SET NULL;

CREATE INDEX ix_item_facturable_novedad ON item_facturable(novedad_orden_id)
    WHERE novedad_orden_id IS NOT NULL;

-- RN-05: una novedad se identifica por sede, periodo, tipo y fecha efectiva. Reprocesar
-- el mismo archivo actualiza la novedad existente en lugar de crear otra; dos cambios
-- de plan en fechas distintas del mismo mes son dos novedades.
CREATE UNIQUE INDEX uq_novedad_orden_clave
    ON novedad_orden(sede_suscripcion_id, periodo_facturacion_id, tipo_novedad_id,
                     COALESCE(fecha_efectiva, DATE '1900-01-01'));
