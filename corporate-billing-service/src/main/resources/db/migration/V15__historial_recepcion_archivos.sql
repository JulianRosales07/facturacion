CREATE TABLE historial_recepcion_archivo (
    id UUID PRIMARY KEY,
    procesado_en TIMESTAMPTZ NOT NULL,
    archivo VARCHAR(255) NOT NULL,
    origen VARCHAR(20) NOT NULL,
    estado VARCHAR(20) NOT NULL,
    sha256 VARCHAR(64),
    nuevos INTEGER NOT NULL DEFAULT 0,
    repetidos INTEGER NOT NULL DEFAULT 0,
    detalle VARCHAR(1500),
    usuario VARCHAR(255)
);
CREATE INDEX idx_historial_recepcion_fecha ON historial_recepcion_archivo(procesado_en DESC);
