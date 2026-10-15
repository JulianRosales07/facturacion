-- Tarifa que tenía la sede justo antes de la novedad (cambio de plan o de servicio).
-- La importación actualiza sede_suscripcion.valor_mensual al valor nuevo; sin este dato,
-- una novedad que llega antes de generar la primera factura del cliente perdía la tarifa
-- anterior y el mes se cobraba completo al plan nuevo. NULL en altas y en novedades
-- anteriores a esta migración.
ALTER TABLE novedad_orden ADD COLUMN valor_anterior NUMERIC(14, 2);
