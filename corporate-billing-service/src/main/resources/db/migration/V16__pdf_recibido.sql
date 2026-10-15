CREATE TABLE pdf_recibido (
    id UUID PRIMARY KEY,
    nombre_original VARCHAR(255) NOT NULL,
    sha256 VARCHAR(64) NOT NULL UNIQUE,
    tamano_bytes BIGINT NOT NULL,
    modificado_en_origen TIMESTAMPTZ NOT NULL,
    ruta_local VARCHAR(500) NOT NULL,
    estado VARCHAR(20) NOT NULL,
    factura_id UUID REFERENCES factura(id),
    motivo VARCHAR(500),
    intentos INTEGER NOT NULL DEFAULT 0,
    recibido_en TIMESTAMPTZ NOT NULL,
    ultimo_intento_en TIMESTAMPTZ,
    asociado_en TIMESTAMPTZ
);

CREATE INDEX idx_pdf_recibido_estado ON pdf_recibido(estado);
CREATE INDEX idx_pdf_recibido_origen ON pdf_recibido(nombre_original, tamano_bytes, modificado_en_origen);
CREATE INDEX idx_historial_recepcion_sha256 ON historial_recepcion_archivo(sha256);
