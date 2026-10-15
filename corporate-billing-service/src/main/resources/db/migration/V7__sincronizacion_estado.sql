-- ==============================================================================
-- Sprint 9: Migración V7 - Estado de Sincronización Horaria (HU-56)
-- ==============================================================================

CREATE TABLE IF NOT EXISTS estado_sincronizacion (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    iniciado_en         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finalizado_en       TIMESTAMPTZ,
    duracion_ms         BIGINT,
    tipo_ejecucion      VARCHAR(20) NOT NULL DEFAULT 'PROGRAMADA', -- PROGRAMADA | MANUAL
    lotes_confirmados   INT DEFAULT 0,
    facturas_detectadas INT DEFAULT 0,
    pdfs_clasificados   INT DEFAULT 0,
    errores             TEXT,
    estado              VARCHAR(20) NOT NULL DEFAULT 'EN_PROGRESO' -- EN_PROGRESO | EXITOSA | FALLIDA
);

CREATE INDEX IF NOT EXISTS idx_estado_sincronizacion_iniciado ON estado_sincronizacion(iniciado_en DESC);
