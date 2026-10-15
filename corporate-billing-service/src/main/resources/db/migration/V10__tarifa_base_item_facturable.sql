-- ==============================================================================
-- Sprint 10: Migración V10 - Tarifa mensual base en el ítem facturable
-- ==============================================================================
-- Problema que resuelve:
--   item_facturable.valor_unitario guarda el importe YA PRORRATEADO (p. ej. 250.000
--   por 15 días), no la tarifa mensual de la que sale (500.000). Cuando un cambio de
--   plan ocurre a mitad de mes, la sincronización de órdenes ya actualizó
--   sede_suscripcion.valor_mensual a la tarifa nueva, así que la tarifa que estaba
--   vigente al inicio del periodo se perdía por completo.
--
--   Consecuencia: el mes entero se cobraba a la tarifa NUEVA. Al subir de plan se
--   facturaba de más; al bajar de plan se facturaba de menos (fuga de ingresos
--   silenciosa, porque nadie reclama una factura baja).
--
-- Con esta columna cada línea conserva la tarifa mensual que la originó, de modo que
-- al regenerar la factura del mismo periodo el calculador puede leer de la versión
-- anterior "esta sede venía a 500.000" y partir el mes correctamente.
--
-- Es NULLABLE a propósito: las líneas emitidas antes de esta migración no tienen el
-- dato y el calculador degrada al comportamiento anterior en esos casos.
-- ==============================================================================

ALTER TABLE item_facturable
    ADD COLUMN IF NOT EXISTS valor_mensual_base NUMERIC(14, 2);

COMMENT ON COLUMN item_facturable.valor_mensual_base IS
    'Tarifa mensual completa que originó la línea, antes de prorratear. Permite reconstruir '
    'la tarifa vigente al inicio del periodo al regenerar la factura.';

-- Backfill conservador: si la línea cubrió el mes comercial completo, el valor unitario
-- YA es la tarifa mensual. Las líneas prorrateadas se dejan en NULL porque el factor de
-- prorrateo no se persistió y la tarifa no es reconstruible sin ambigüedad.
UPDATE item_facturable i
SET valor_mensual_base = i.valor_unitario
FROM factura f, periodo_facturacion p
WHERE i.factura_id = f.id
  AND f.periodo_facturacion_id = p.id
  AND i.valor_mensual_base IS NULL
  AND i.tipo_item = 'SERVICIO_CORPORATIVO'
  AND i.descripcion NOT LIKE 'CUOTA INSTALACION%'
  -- La descripción codifica el rango cobrado como "GR DD-DD MMM YYYY". Solo se
  -- considera mes completo si arranca el día 01 y termina el último día del periodo.
  AND i.descripcion LIKE '%GR 01-' || to_char(p.fecha_fin, 'DD') || '%';

CREATE INDEX IF NOT EXISTS idx_item_facturable_sede ON item_facturable (sede_suscripcion_id)
    WHERE sede_suscripcion_id IS NOT NULL;
