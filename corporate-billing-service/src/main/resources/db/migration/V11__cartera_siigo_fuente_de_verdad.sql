-- ==============================================================================
-- Sprint 13: Migración V11 - El saldo de Siigo es la fuente de verdad de la cartera
-- ==============================================================================
-- Decisión de negocio: el corte de cartera que Siigo deja en la carpeta compartida
-- (saldos_de_cartera.xlsx, columna SALDO) es la FUENTE DE VERDAD del saldo pendiente.
-- El saldo que el microservicio calculaba por su cuenta
-- (valor_total - notas_credito - pagos) pasa a ser una proyección local que se
-- conserva únicamente para conciliar y detectar diferencias.
--
-- Estructura real del archivo (verificada sobre el ejemplo de 1.048 filas):
--   Encabezados en la fila 5, datos desde la fila 6, hoja única.
--   A IDENTIFICACIÓN  -> NIT del cliente
--   C CUENTA          -> cuenta contable de cartera (1305051001..1305051007)
--   D TIPO COMPROBANTE-> 'F'
--   E CÓDIGO COMPROB. -> 1 => F1 (FESP) | 20 => F20 (FEMG)
--   F NÚMERO DOCUMENTO-> consecutivo de la factura
--   H FECHA VENCIM.   -> entero yyyyMMdd
--   L SALDO           -> saldo pendiente (double en el xlsx; se redondea HALF_UP)
--   Q FECHA DOCUMENTO -> entero yyyyMMdd
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Saldo autoritativo en la factura
-- ------------------------------------------------------------------------------
ALTER TABLE factura
    ADD COLUMN IF NOT EXISTS saldo_siigo               NUMERIC(14, 2),
    ADD COLUMN IF NOT EXISTS saldo_siigo_actualizado_en TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS saldo_siigo_snapshot_id    UUID,
    ADD COLUMN IF NOT EXISTS saldo_siigo_corte_en       TIMESTAMPTZ;

COMMENT ON COLUMN factura.saldo_siigo IS
    'Saldo pendiente según el corte de cartera de Siigo. FUENTE DE VERDAD. NULL = nunca '
    'se ha recibido un corte para esta factura, en cuyo caso rige el saldo calculado localmente.';
COMMENT ON COLUMN factura.saldo_siigo_corte_en IS
    'Fecha de generación del corte que produjo este saldo. Se usa para no dejar que un '
    'archivo viejo sobrescriba un movimiento local más reciente.';

CREATE INDEX IF NOT EXISTS idx_factura_saldo_siigo ON factura (saldo_siigo)
    WHERE saldo_siigo IS NOT NULL;

-- ------------------------------------------------------------------------------
-- 2. Corte de cartera ingerido (cabecera)
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cartera_snapshot (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre_archivo      VARCHAR(255) NOT NULL,
    ruta_archivo        VARCHAR(500),
    hash_sha256         VARCHAR(64),
    tamano_bytes        BIGINT,
    generado_en         TIMESTAMPTZ NOT NULL,
    filas_totales       INT NOT NULL DEFAULT 0,
    filas_emparejadas   INT NOT NULL DEFAULT 0,
    filas_sin_factura   INT NOT NULL DEFAULT 0,
    filas_con_error     INT NOT NULL DEFAULT 0,
    facturas_sin_corte  INT NOT NULL DEFAULT 0,
    estado              VARCHAR(20) NOT NULL DEFAULT 'PROCESANDO',
    errores             TEXT,
    ingerido_en         TIMESTAMPTZ NOT NULL DEFAULT now(),
    ingerido_por        VARCHAR(100)
);

COMMENT ON COLUMN cartera_snapshot.hash_sha256 IS
    'Permite saltar un archivo ya ingerido: el lector de la carpeta compartida no mueve '
    'ni marca los archivos procesados, así que el mismo corte reaparece en cada ciclo.';
COMMENT ON COLUMN cartera_snapshot.facturas_sin_corte IS
    'Facturas vigentes del sistema que NO aparecieron en el corte. Ver la política '
    'siigo.cartera.tratar-ausencia-como-saldo-cero antes de interpretarlo como pagadas.';

CREATE UNIQUE INDEX IF NOT EXISTS uq_cartera_snapshot_hash ON cartera_snapshot (hash_sha256)
    WHERE hash_sha256 IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cartera_snapshot_generado ON cartera_snapshot (generado_en DESC);

-- ------------------------------------------------------------------------------
-- 3. Detalle fila por fila (auditoría completa, incluidas las no emparejadas)
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cartera_snapshot_linea (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cartera_snapshot_id  UUID NOT NULL REFERENCES cartera_snapshot(id) ON DELETE CASCADE,
    numero_fila          INT NOT NULL,
    nit_archivo          VARCHAR(20),
    cuenta_contable      VARCHAR(20),
    tipo_comprobante     VARCHAR(5),
    codigo_comprobante   INT,
    numero_documento     VARCHAR(30),
    tipo_facturacion     tipo_facturacion,
    saldo                NUMERIC(14, 2),
    -- Saldo que el microservicio tenía calculado en el momento de aplicar la fila, y su
    -- diferencia contra Siigo. Se congelan aquí para que el reporte de conciliación sea una
    -- lectura pura: los valores de los que depende el cálculo local (notas crédito y pagos)
    -- son campos derivados que no se pueden reconstruir en SQL.
    saldo_calculado      NUMERIC(14, 2),
    diferencia           NUMERIC(14, 2),
    fecha_vencimiento    DATE,
    fecha_documento      DATE,
    centro_costo         VARCHAR(20),
    codigo_ciudad        VARCHAR(20),
    codigo_zona          VARCHAR(20),
    factura_id           UUID REFERENCES factura(id),
    estado_linea         VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE',
    mensaje              VARCHAR(500),
    CONSTRAINT uq_cartera_linea_fila UNIQUE (cartera_snapshot_id, numero_fila)
);

COMMENT ON COLUMN cartera_snapshot_linea.estado_linea IS
    'APLICADA | SIN_FACTURA | NIT_DISCREPANTE | CORTE_OBSOLETO | ERROR_FORMATO';

CREATE INDEX IF NOT EXISTS idx_cartera_linea_factura ON cartera_snapshot_linea (factura_id)
    WHERE factura_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cartera_linea_documento
    ON cartera_snapshot_linea (codigo_comprobante, numero_documento);
CREATE INDEX IF NOT EXISTS idx_cartera_linea_no_aplicada
    ON cartera_snapshot_linea (cartera_snapshot_id) WHERE estado_linea <> 'APLICADA';
CREATE INDEX IF NOT EXISTS idx_cartera_linea_discrepancia
    ON cartera_snapshot_linea (cartera_snapshot_id)
    WHERE diferencia IS NOT NULL AND diferencia <> 0;
