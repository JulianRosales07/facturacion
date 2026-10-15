-- Detalle de cobros por bloques (Cargos fijos / Otros conceptos).

-- Rango que cubre cada concepto, para mostrar "fecha inicial / fecha final" sin
-- interpretar la descripción. NULL en líneas anteriores a esta migración.
ALTER TABLE item_facturable ADD COLUMN fecha_desde DATE;
ALTER TABLE item_facturable ADD COLUMN fecha_hasta DATE;

-- Descuento por valor exacto (bajada de plan aplicada sobre el cargo fijo). Cuando está
-- informado manda sobre porcentaje_descuento, que con dos decimales perdería pesos.
ALTER TABLE item_facturable ADD COLUMN valor_descuento_fijo NUMERIC(14, 2)
    CHECK (valor_descuento_fijo IS NULL OR valor_descuento_fijo >= 0);
