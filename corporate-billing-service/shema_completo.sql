-- =====================================================================
-- Microservicio de Facturación Corporativa (B2B / Multi-Sede)
-- Esquema Simplificado: Facturación Core, Sedes, Novedades y Pagos
-- (Excluye regulaciones específicas de DIAN y asientos/exportaciones contables)
-- Motor: PostgreSQL 13+
-- =====================================================================
-- Convenciones:
--  * UUID v4 como PK (gen_random_uuid() nativo en PostgreSQL 13+)
--  * Soft delete vía columna "estado" (ACTIVO/INACTIVO) en maestros
--  * Tipo polimórfico "tipo_item_facturable" para servicios y SVA
--  * Snapshot fiscal inmutable en cabecera de facturas
--  * Índice parcial para garantizar 1 factura activa por cliente y periodo
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------
-- 1. TIPOS ENUMERADOS (ENUMS)
-- ---------------------------------------------------------------------
CREATE TYPE tipo_facturacion           AS ENUM ('F1', 'F20');
CREATE TYPE estado_registro            AS ENUM ('ACTIVO', 'INACTIVO');
CREATE TYPE estado_factura             AS ENUM ('BORRADOR', 'CONSOLIDADA', 'PAGADA', 'ANULADA');
CREATE TYPE tipo_documento_ajuste      AS ENUM ('NOTA_CREDITO', 'NOTA_DEBITO');
CREATE TYPE estado_documento_ajuste    AS ENUM ('BORRADOR', 'APLICADO', 'ANULADO');
CREATE TYPE origen_orden               AS ENUM ('SPE', 'MEGA_RED', 'ENLACES');
CREATE TYPE tipo_item_facturable       AS ENUM ('SERVICIO_CORPORATIVO', 'SVA_STREAMING', 'SVA_PRODUCTO');
CREATE TYPE estado_pago                AS ENUM ('APLICADO', 'ANULADO');
CREATE TYPE estado_archivo             AS ENUM ('RECIBIDO', 'PROCESANDO', 'PROCESADO', 'ERROR');
CREATE TYPE estado_linea_importada     AS ENUM ('PENDIENTE', 'PROCESADA', 'ERROR');

-- ---------------------------------------------------------------------
-- 2. CATÁLOGOS BASE Y MAESTROS COMERCIALES
-- ---------------------------------------------------------------------

-- MARCA: Catálogo de marcas comerciales bajo las cuales opera la empresa
CREATE TABLE marca (
    id      SMALLINT PRIMARY KEY,
    nombre  VARCHAR(50) NOT NULL UNIQUE
);

INSERT INTO marca (id, nombre) VALUES
    (1, 'Sistemas Palacios'),
    (2, 'Meganet'),
    (3, 'Enlaces');

-- ZONA: Catálogo de zonas geográficas / comerciales
CREATE TABLE zona (
    id      SMALLINT PRIMARY KEY,
    nombre  VARCHAR(50) NOT NULL UNIQUE
);

INSERT INTO zona (id, nombre) VALUES
    (1, 'Pasto'),
    (2, 'Norte'),
    (3, 'Sur'),
    (4, 'Occidente'),
    (5, 'Costa');

-- CLIENTE CORPORATIVO: Empresa o persona jurídica identificada por su NIT
CREATE TABLE cliente_corporativo (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nit                     VARCHAR(20)  NOT NULL UNIQUE,
    razon_social            VARCHAR(255) NOT NULL,
    direccion_fiscal        VARCHAR(255),
    email_facturacion       VARCHAR(150),
    telefono                VARCHAR(50),
    marca_id                SMALLINT NOT NULL REFERENCES marca(id),
    zona_id                 SMALLINT NOT NULL REFERENCES zona(id),
    tipo_facturacion        tipo_facturacion NOT NULL DEFAULT 'F1',
    aplica_retencion        BOOLEAN NOT NULL DEFAULT FALSE,
    porcentaje_retefuente   NUMERIC(5,2) NOT NULL DEFAULT 0,
    porcentaje_reteica      NUMERIC(5,2) NOT NULL DEFAULT 0,
    porcentaje_reteiva      NUMERIC(5,2) NOT NULL DEFAULT 0,
    estado                  estado_registro NOT NULL DEFAULT 'ACTIVO',
    creado_en               TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cliente_nit ON cliente_corporativo(nit);
CREATE INDEX idx_cliente_marca ON cliente_corporativo(marca_id);
CREATE INDEX idx_cliente_zona ON cliente_corporativo(zona_id);

-- SEDE / SUSCRIPCIÓN: Puntos físicos o enlaces provistos al cliente
CREATE TABLE sede_suscripcion (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_corporativo_id  UUID NOT NULL REFERENCES cliente_corporativo(id),
    codigo_orden_externo    VARCHAR(50) NOT NULL,
    origen                  origen_orden NOT NULL,
    nombre_sede             VARCHAR(100),
    direccion               VARCHAR(255),
    estado                  estado_registro NOT NULL DEFAULT 'ACTIVO',
    creado_en               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (codigo_orden_externo, origen)
);

CREATE INDEX idx_sede_cliente ON sede_suscripcion(cliente_corporativo_id);

-- ---------------------------------------------------------------------
-- 3. PERIODOS CONTABLES Y NOVEDADES OPERATIVAS
-- ---------------------------------------------------------------------

-- PERIODO DE FACTURACIÓN: Ciclo mensual de facturación
CREATE TABLE periodo_facturacion (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo        VARCHAR(7) NOT NULL UNIQUE CHECK (codigo ~ '^\d{4}-\d{2}$'), -- Formato 'YYYY-MM'
    tipo_corte    VARCHAR(20) NOT NULL,                                       -- ADELANTADO | VENCIDO
    fecha_inicio  DATE NOT NULL,
    fecha_fin     DATE NOT NULL,
    estado        VARCHAR(20) NOT NULL DEFAULT 'ABIERTO',                     -- ABIERTO | CERRADO
    creado_en     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- TIPO DE NOVEDAD: Catálogo de eventos operativos sobre las sedes
CREATE TABLE tipo_novedad (
    id      SMALLINT PRIMARY KEY,
    nombre  VARCHAR(50) NOT NULL UNIQUE
);

INSERT INTO tipo_novedad (id, nombre) VALUES
    (1, 'ALTA'),
    (2, 'BAJA'),
    (3, 'CAMBIO_PLAN'),
    (4, 'CAMBIO_SERVICIO'),
    (5, 'SUSPENSION'),
    (6, 'REACTIVACION'),
    (7, 'TRASLADO_DIRECCION');

-- NOVEDAD DE ORDEN: Evento ocurrido en una sede dentro de un periodo
CREATE TABLE novedad_orden (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sede_suscripcion_id     UUID NOT NULL REFERENCES sede_suscripcion(id),
    tipo_novedad_id         SMALLINT NOT NULL REFERENCES tipo_novedad(id),
    periodo_facturacion_id  UUID NOT NULL REFERENCES periodo_facturacion(id),
    incluir_en_consolidado  BOOLEAN NOT NULL DEFAULT TRUE,
    observacion             VARCHAR(255),
    creado_en               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_novedad_sede_periodo ON novedad_orden(sede_suscripcion_id, periodo_facturacion_id);
CREATE INDEX idx_novedad_periodo ON novedad_orden(periodo_facturacion_id);

-- ---------------------------------------------------------------------
-- 4. FACTURACIÓN CONSOLIDADA CORE (CABECERA Y DETALLE)
-- ---------------------------------------------------------------------

-- FACTURA: Cabecera consolidada de todas las sedes del cliente en el periodo
CREATE TABLE factura (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_corporativo_id      UUID NOT NULL REFERENCES cliente_corporativo(id),
    periodo_facturacion_id      UUID NOT NULL REFERENCES periodo_facturacion(id),
    tipo_facturacion            tipo_facturacion NOT NULL,
    numero_factura              VARCHAR(30),

    -- Snapshot de datos del cliente al momento de facturar (inmutabilidad histórica)
    nit_cliente                 VARCHAR(20)  NOT NULL,
    razon_social_cliente        VARCHAR(255) NOT NULL,
    direccion_fiscal_cliente    VARCHAR(255),
    email_facturacion_cliente   VARCHAR(150),
    datos_fiscales_snapshot     JSONB,

    -- Desglose monetario e impuestos
    valor_bruto                 NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_descuento             NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_subtotal              NUMERIC(14,2) NOT NULL DEFAULT 0,  -- Base gravable
    valor_iva                   NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_retefuente            NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_reteiva               NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_reteica               NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_total_retenciones     NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_neto                  NUMERIC(14,2) NOT NULL DEFAULT 0,  -- Total a pagar

    fecha_emision               DATE NOT NULL DEFAULT CURRENT_DATE,
    fecha_vencimiento           DATE,
    forma_pago                  VARCHAR(20) DEFAULT 'CREDITO',

    estado                      estado_factura NOT NULL DEFAULT 'BORRADOR',
    anulado_en                  TIMESTAMPTZ,
    motivo_anulacion            VARCHAR(255),
    creado_en                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Índice parcial único: permite refacturar solo si la anterior está ANULADA
CREATE UNIQUE INDEX uq_factura_cliente_periodo_activa
    ON factura(cliente_corporativo_id, periodo_facturacion_id)
    WHERE estado != 'ANULADA';

CREATE INDEX idx_factura_cliente ON factura(cliente_corporativo_id);
CREATE INDEX idx_factura_periodo ON factura(periodo_facturacion_id);
CREATE INDEX idx_factura_numero ON factura(numero_factura) WHERE numero_factura IS NOT NULL;
CREATE INDEX idx_factura_estado ON factura(estado);

-- ÍTEM FACTURABLE: Línea de cobro (Servicios por sede o SVA)
CREATE TABLE item_facturable (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factura_id             UUID NOT NULL REFERENCES factura(id) ON DELETE CASCADE,
    tipo_item              tipo_item_facturable NOT NULL,
    sede_suscripcion_id    UUID REFERENCES sede_suscripcion(id),
    descripcion            VARCHAR(255) NOT NULL,
    cantidad               NUMERIC(10,2) NOT NULL DEFAULT 1,
    valor_unitario         NUMERIC(14,2) NOT NULL,
    porcentaje_descuento   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valor_descuento        NUMERIC(14,2) NOT NULL DEFAULT 0,
    base_gravable          NUMERIC(14,2) NOT NULL DEFAULT 0,
    gravado                BOOLEAN NOT NULL DEFAULT TRUE,
    porcentaje_iva         NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valor_iva              NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_total            NUMERIC(14,2) NOT NULL,
    atributos_extra        JSONB,
    creado_en              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_item_factura ON item_facturable(factura_id);
CREATE INDEX idx_item_sede ON item_facturable(sede_suscripcion_id) WHERE sede_suscripcion_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 5. METADATOS Y CAMPOS DINÁMICOS EN FACTURA (EAV)
-- ---------------------------------------------------------------------

CREATE TABLE definicion_campo_extra (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clave_campo   VARCHAR(50)  NOT NULL UNIQUE,         -- ej: 'orden_compra', 'centro_costo'
    etiqueta      VARCHAR(100) NOT NULL,
    tipo_dato     VARCHAR(20)  NOT NULL DEFAULT 'TEXTO', -- TEXTO | NUMERO | FECHA | BOOLEANO
    obligatorio   BOOLEAN NOT NULL DEFAULT FALSE,
    estado        estado_registro NOT NULL DEFAULT 'ACTIVO',
    creado_en     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE campo_extra_factura (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factura_id                  UUID NOT NULL REFERENCES factura(id) ON DELETE CASCADE,
    definicion_campo_extra_id   UUID NOT NULL REFERENCES definicion_campo_extra(id),
    valor                       TEXT NOT NULL,
    creado_en                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (factura_id, definicion_campo_extra_id)
);

CREATE INDEX idx_campo_extra_factura_factura ON campo_extra_factura(factura_id);

-- ---------------------------------------------------------------------
-- 6. DOCUMENTOS DE AJUSTE (NOTAS CRÉDITO Y DÉBITO)
-- ---------------------------------------------------------------------

CREATE TABLE documento_ajuste (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factura_id                  UUID NOT NULL REFERENCES factura(id),
    tipo                        tipo_documento_ajuste NOT NULL,     -- NOTA_CREDITO | NOTA_DEBITO
    numero_documento            VARCHAR(30),
    motivo                      VARCHAR(255) NOT NULL,
    valor_bruto                 NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_descuento             NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_subtotal              NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_iva                   NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_neto                  NUMERIC(14,2) NOT NULL DEFAULT 0,
    estado                      estado_documento_ajuste NOT NULL DEFAULT 'BORRADOR',
    creado_en                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_doc_ajuste_factura ON documento_ajuste(factura_id);

CREATE TABLE item_documento_ajuste (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    documento_ajuste_id     UUID NOT NULL REFERENCES documento_ajuste(id) ON DELETE CASCADE,
    item_facturable_id      UUID REFERENCES item_facturable(id),
    descripcion             VARCHAR(255) NOT NULL,
    cantidad                NUMERIC(10,2) NOT NULL DEFAULT 1,
    valor_unitario          NUMERIC(14,2) NOT NULL,
    valor_descuento         NUMERIC(14,2) NOT NULL DEFAULT 0,
    base_gravable           NUMERIC(14,2) NOT NULL DEFAULT 0,
    porcentaje_iva          NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valor_iva               NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_total             NUMERIC(14,2) NOT NULL,
    creado_en               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_item_ajuste_doc ON item_documento_ajuste(documento_ajuste_id);

-- ---------------------------------------------------------------------
-- 7. GESTIÓN DE RECAUDOS Y PAGOS DE FACTURA
-- ---------------------------------------------------------------------

CREATE TABLE pago_factura (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factura_id          UUID NOT NULL REFERENCES factura(id),
    referencia_pago     VARCHAR(100) NOT NULL,
    medio_pago          VARCHAR(50)  NOT NULL, -- TRANSFERENCIA, CONSIGNACION, PASARELA, CHEQUE
    banco               VARCHAR(50),
    valor               NUMERIC(14,2) NOT NULL,
    fecha_pago          DATE NOT NULL,
    estado              estado_pago NOT NULL DEFAULT 'APLICADO',
    observacion         VARCHAR(255),
    creado_en           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pago_factura ON pago_factura(factura_id);

-- ---------------------------------------------------------------------
-- 8. INGESTA Y STAGING DE ARCHIVOS PLANOS DE ÓRDENES
-- ---------------------------------------------------------------------

CREATE TABLE archivo_importado (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre_archivo      VARCHAR(255) NOT NULL,
    origen              origen_orden NOT NULL,
    estado              estado_archivo NOT NULL DEFAULT 'RECIBIDO',
    total_registros     INT,
    registros_con_error INT DEFAULT 0,
    recibido_en         TIMESTAMPTZ NOT NULL DEFAULT now(),
    procesado_en        TIMESTAMPTZ
);

CREATE TABLE archivo_importado_linea (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    archivo_importado_id   UUID NOT NULL REFERENCES archivo_importado(id) ON DELETE CASCADE,
    numero_linea           INT NOT NULL,
    contenido_crudo        JSONB NOT NULL,
    estado_linea           estado_linea_importada NOT NULL DEFAULT 'PENDIENTE',
    mensaje_error          VARCHAR(500),
    sede_suscripcion_id    UUID REFERENCES sede_suscripcion(id),
    novedad_orden_id       UUID REFERENCES novedad_orden(id),
    procesado_en           TIMESTAMPTZ,
    creado_en              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (archivo_importado_id, numero_linea)
);

CREATE INDEX idx_linea_archivo ON archivo_importado_linea(archivo_importado_id);
CREATE INDEX idx_linea_estado ON archivo_importado_linea(estado_linea);
