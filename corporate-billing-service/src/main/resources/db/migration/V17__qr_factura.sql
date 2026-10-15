-- Sprint 14 / Requerimiento: Extracción y persistencia del QR real de facturas oficiales
ALTER TABLE factura ADD COLUMN IF NOT EXISTS qr_contenido TEXT;
ALTER TABLE factura ADD COLUMN IF NOT EXISTS qr_imagen_path VARCHAR(500);

CREATE INDEX IF NOT EXISTS idx_factura_qr_contenido ON factura(id) WHERE qr_contenido IS NOT NULL;
