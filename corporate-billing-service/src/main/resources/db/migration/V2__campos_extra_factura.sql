-- =====================================================================
-- Migración V2: Metadatos y Campos Dinámicos en Factura (EAV)
-- Tablas: definicion_campo_extra, campo_extra_factura
-- =====================================================================

CREATE TABLE IF NOT EXISTS definicion_campo_extra (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clave_campo   VARCHAR(50)  NOT NULL UNIQUE,
    etiqueta      VARCHAR(100) NOT NULL,
    tipo_dato     VARCHAR(20)  NOT NULL DEFAULT 'TEXTO', -- TEXTO | NUMERO | FECHA | BOOLEANO
    obligatorio   BOOLEAN NOT NULL DEFAULT FALSE,
    estado        estado_registro NOT NULL DEFAULT 'ACTIVO',
    creado_en     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS campo_extra_factura (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factura_id                  UUID NOT NULL REFERENCES factura(id) ON DELETE CASCADE,
    definicion_campo_extra_id   UUID NOT NULL REFERENCES definicion_campo_extra(id),
    valor                       TEXT NOT NULL,
    creado_en                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (factura_id, definicion_campo_extra_id)
);

CREATE INDEX IF NOT EXISTS idx_campo_extra_factura_factura ON campo_extra_factura(factura_id);
