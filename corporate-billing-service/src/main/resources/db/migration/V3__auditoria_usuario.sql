-- Sprint 8: Auditoria de usuarios (HU-34 / RF-33)
ALTER TABLE factura ADD COLUMN IF NOT EXISTS creado_por VARCHAR(100);
ALTER TABLE factura ADD COLUMN IF NOT EXISTS anulado_por VARCHAR(100);

ALTER TABLE lote_exportacion ADD COLUMN IF NOT EXISTS generado_por VARCHAR(100);
ALTER TABLE lote_exportacion ADD COLUMN IF NOT EXISTS confirmado_por VARCHAR(100);

ALTER TABLE documento_ajuste ADD COLUMN IF NOT EXISTS creado_por VARCHAR(100);

ALTER TABLE vigencia_concepto ADD COLUMN IF NOT EXISTS creado_por VARCHAR(100);
ALTER TABLE vigencia_concepto ADD COLUMN IF NOT EXISTS actualizado_por VARCHAR(100);

ALTER TABLE perfil_tributario_cliente ADD COLUMN IF NOT EXISTS creado_por VARCHAR(100);
