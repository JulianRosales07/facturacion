-- ==============================================================================
-- Sprint 10: Migración V9 - Facturación versionada (Opción A)
-- ==============================================================================
-- Habilita el ciclo completo:
--   1. Se ingiere la factura oficial desde la carpeta compartida de Siigo.
--   2. Llegan novedades nuevas del sistema de órdenes para el MISMO periodo.
--   3. Se regenera la factura del cliente acumulando esas novedades.
--   4. La versión anterior se conserva íntegra en estado 'REEMPLAZADA' y queda
--      encadenada a la nueva, junto con su PDF original.
--
-- Invariante clave: una sola factura VIGENTE por cliente + periodo.
-- Vigente = estado NOT IN ('ANULADA', 'REEMPLAZADA').
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Columnas de versionado en factura
-- ------------------------------------------------------------------------------
ALTER TABLE factura
    ADD COLUMN IF NOT EXISTS numero_version      INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS factura_origen_id   UUID,
    ADD COLUMN IF NOT EXISTS factura_anterior_id UUID,
    ADD COLUMN IF NOT EXISTS reemplazada_por_id  UUID,
    ADD COLUMN IF NOT EXISTS reemplazada_en      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS motivo_regeneracion VARCHAR(255),
    ADD COLUMN IF NOT EXISTS regenerado_por      VARCHAR(100);

COMMENT ON COLUMN factura.numero_version      IS 'Versión dentro de la cadena de refacturación. La primera emisión es 1.';
COMMENT ON COLUMN factura.factura_origen_id   IS 'Raíz de la cadena de versiones. En la versión 1 apunta a sí misma.';
COMMENT ON COLUMN factura.factura_anterior_id IS 'Versión inmediatamente anterior (NULL en la versión 1).';
COMMENT ON COLUMN factura.reemplazada_por_id  IS 'Versión que sustituyó a esta factura (NULL si es la vigente).';
COMMENT ON COLUMN factura.motivo_regeneracion IS 'Motivo por el cual esta versión fue generada.';

-- Backfill: toda factura preexistente es la raíz de su propia cadena.
UPDATE factura SET factura_origen_id = id WHERE factura_origen_id IS NULL;

ALTER TABLE factura ALTER COLUMN factura_origen_id SET NOT NULL;

ALTER TABLE factura
    ADD CONSTRAINT fk_factura_origen
        FOREIGN KEY (factura_origen_id) REFERENCES factura(id),
    ADD CONSTRAINT fk_factura_anterior
        FOREIGN KEY (factura_anterior_id) REFERENCES factura(id),
    -- DEFERRABLE porque la referencia es circular en el tiempo: al regenerar, la versión
    -- anterior debe marcarse como reemplazada ANTES de insertar la nueva (para no violar
    -- el índice de unicidad vigente), pero apunta a una fila que aún no existe. Con la
    -- verificación diferida al commit ambas condiciones se satisfacen.
    ADD CONSTRAINT fk_factura_reemplazada_por
        FOREIGN KEY (reemplazada_por_id) REFERENCES factura(id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE factura
    ADD CONSTRAINT chk_factura_version_positiva CHECK (numero_version >= 1),
    -- La versión 1 no puede tener antecesora; a partir de la 2 es obligatoria.
    ADD CONSTRAINT chk_factura_cadena_coherente CHECK (
        (numero_version = 1 AND factura_anterior_id IS NULL)
        OR (numero_version > 1 AND factura_anterior_id IS NOT NULL)
    ),
    -- Una factura no puede reemplazarse a sí misma.
    ADD CONSTRAINT chk_factura_no_autoreemplazo CHECK (
        reemplazada_por_id IS NULL OR reemplazada_por_id <> id
    );

-- ------------------------------------------------------------------------------
-- 2. Redefinir la unicidad: una factura VIGENTE por cliente + periodo
-- ------------------------------------------------------------------------------
-- Se conserva el nombre original del índice porque la capa de servicio y las
-- pruebas de invariantes lo referencian explícitamente.
DROP INDEX IF EXISTS uq_factura_cliente_periodo_activa;

CREATE UNIQUE INDEX uq_factura_cliente_periodo_activa
    ON factura (cliente_corporativo_id, periodo_facturacion_id)
    WHERE estado <> 'ANULADA' AND estado <> 'REEMPLAZADA';

-- Dentro de una misma cadena no pueden repetirse números de versión.
CREATE UNIQUE INDEX IF NOT EXISTS uq_factura_cadena_version
    ON factura (factura_origen_id, numero_version);

CREATE INDEX IF NOT EXISTS idx_factura_origen ON factura (factura_origen_id);
CREATE INDEX IF NOT EXISTS idx_factura_reemplazada_por ON factura (reemplazada_por_id)
    WHERE reemplazada_por_id IS NOT NULL;

-- ------------------------------------------------------------------------------
-- 3. Histórico de PDF oficiales
-- ------------------------------------------------------------------------------
-- Antes de esta migración, la ingesta desde la carpeta compartida sobrescribía
-- factura.ruta_pdf sin dejar rastro del archivo anterior. Aquí se preserva cada
-- PDF que alguna vez estuvo asociado a la factura.
CREATE TABLE IF NOT EXISTS factura_pdf_historico (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factura_id        UUID NOT NULL REFERENCES factura(id) ON DELETE CASCADE,
    numero_version    INT NOT NULL,
    ruta_pdf          VARCHAR(500) NOT NULL,
    nombre_archivo    VARCHAR(255),
    hash_sha256       VARCHAR(64),
    tamano_bytes      BIGINT,
    numero_factura    VARCHAR(30),
    cufe              VARCHAR(96),
    origen            VARCHAR(40) NOT NULL DEFAULT 'CARPETA_COMPARTIDA_SIIGO',
    motivo            VARCHAR(255),
    archivado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    archivado_por     VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_factura_pdf_historico_factura
    ON factura_pdf_historico (factura_id, archivado_en DESC);
CREATE INDEX IF NOT EXISTS idx_factura_pdf_historico_hash
    ON factura_pdf_historico (hash_sha256) WHERE hash_sha256 IS NOT NULL;

-- ------------------------------------------------------------------------------
-- 4. Trazabilidad novedad -> factura
-- ------------------------------------------------------------------------------
-- Sin esto, una misma novedad puede aplicarse varias veces al regenerar y no hay
-- forma de auditar qué novedad alimentó qué versión de la factura.
ALTER TABLE novedad_orden
    ADD COLUMN IF NOT EXISTS factura_id   UUID REFERENCES factura(id),
    ADD COLUMN IF NOT EXISTS facturado_en TIMESTAMPTZ;

COMMENT ON COLUMN novedad_orden.factura_id IS 'Versión de factura vigente que consumió esta novedad.';

CREATE INDEX IF NOT EXISTS idx_novedad_factura ON novedad_orden (factura_id)
    WHERE factura_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_novedad_periodo_pendiente ON novedad_orden (periodo_facturacion_id)
    WHERE factura_id IS NULL;
