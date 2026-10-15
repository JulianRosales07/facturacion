CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ENUMS
CREATE TYPE tipo_facturacion        AS ENUM ('F1', 'F20');
CREATE TYPE estado_registro         AS ENUM ('ACTIVO', 'INACTIVO');
CREATE TYPE estado_factura          AS ENUM ('BORRADOR', 'CONSOLIDADA', 'PAGADA', 'ANULADA');
CREATE TYPE tipo_documento_ajuste   AS ENUM ('NOTA_CREDITO', 'NOTA_DEBITO');
CREATE TYPE estado_documento_ajuste AS ENUM ('BORRADOR', 'APLICADO', 'ANULADO');
CREATE TYPE origen_orden            AS ENUM ('SPE', 'MEGA_RED', 'ENLACES');
CREATE TYPE tipo_item_facturable    AS ENUM ('SERVICIO_CORPORATIVO', 'SVA_STREAMING', 'SVA_PRODUCTO');
CREATE TYPE estado_pago             AS ENUM ('APLICADO', 'ANULADO');
CREATE TYPE estado_archivo          AS ENUM ('RECIBIDO', 'PROCESANDO', 'PROCESADO', 'ERROR');
CREATE TYPE estado_linea_importada  AS ENUM ('PENDIENTE', 'PROCESADA', 'ERROR');
CREATE TYPE estado_lote             AS ENUM ('GENERADO', 'ENVIADO', 'CONFIRMADO', 'ERROR');
CREATE TYPE estado_cuota            AS ENUM ('PENDIENTE', 'FACTURANDO', 'PAGADA', 'ANULADA');
CREATE TYPE naturaleza_contable     AS ENUM ('D', 'C');
CREATE TYPE tipo_linea_asiento      AS ENUM ('INGRESO', 'IVA', 'CARTERA', 'ORDEN');  -- el asiento no incluye retenciones
CREATE TYPE clasificacion_servicio  AS ENUM ('INTERNET', 'SOFTWARE');

-- CATÁLOGOS
CREATE TABLE marca (
    id SMALLINT PRIMARY KEY,
    nombre VARCHAR(50) NOT NULL UNIQUE
);
INSERT INTO marca (id, nombre) VALUES
    (1, 'Sistemas Palacios'),
    (2, 'Meganet'),
    (3, 'Enlaces');

CREATE TABLE zona (
    id SMALLINT PRIMARY KEY,
    nombre VARCHAR(50) NOT NULL UNIQUE
);
INSERT INTO zona (id, nombre) VALUES
    (1, 'Pasto'),
    (2, 'Norte'),
    (3, 'Sur'),
    (4, 'Occidente'),
    (5, 'Costa');

CREATE TABLE tipo_novedad (
    id SMALLINT PRIMARY KEY,
    nombre VARCHAR(50) NOT NULL UNIQUE
);
INSERT INTO tipo_novedad (id, nombre) VALUES
    (1, 'ALTA'),
    (2, 'BAJA'),
    (3, 'CAMBIO_PLAN'),
    (4, 'CAMBIO_SERVICIO'),
    (5, 'SUSPENSION'),
    (6, 'REACTIVACION'),
    (7, 'TRASLADO_DIRECCION');

-- CONFIGURACIÓN TRIBUTARIA (administrada por este micro; solo para el neto a pagar)
-- Conceptos: retefuente, reteica (territorial), reteiva, estampilla.
CREATE TABLE concepto_tributario (
    id          SMALLINT PRIMARY KEY,
    codigo      VARCHAR(20) NOT NULL UNIQUE,   -- RETEFUENTE, RETEICA, RETEIVA, ESTAMPILLA
    nombre      VARCHAR(80) NOT NULL,
    territorial BOOLEAN NOT NULL DEFAULT FALSE  -- reteica = TRUE (depende del municipio)
);
INSERT INTO concepto_tributario (id, codigo, nombre, territorial) VALUES
    (1, 'RETEFUENTE', 'Retención en la fuente', FALSE),
    (2, 'RETEICA', 'Retención de ICA', TRUE),
    (3, 'RETEIVA', 'Retención de IVA', FALSE),
    (4, 'ESTAMPILLA', 'Estampillas', TRUE);

-- Base + porcentaje con VIGENCIA (global; cambia por decisión del gobierno, varias veces al año).
CREATE TABLE vigencia_concepto (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    concepto_tributario_id SMALLINT NOT NULL REFERENCES concepto_tributario(id),
    clasificacion          clasificacion_servicio,  -- NULL = aplica a cualquiera
    municipio              VARCHAR(60),             -- reteica territorial; NULL = general
    base_minima            NUMERIC(14,2) NOT NULL DEFAULT 0,  -- base a partir de la cual retiene
    porcentaje             NUMERIC(5,2)  NOT NULL,
    vigente_desde          DATE NOT NULL,
    vigente_hasta          DATE,                    -- NULL = vigente
    estado                 estado_registro NOT NULL DEFAULT 'ACTIVO'
);
CREATE INDEX idx_vigencia_concepto ON vigencia_concepto(concepto_tributario_id, vigente_desde);

-- CLIENTE CORPORATIVO
CREATE TABLE cliente_corporativo (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nit                   VARCHAR(20)  NOT NULL UNIQUE,
    razon_social          VARCHAR(255) NOT NULL,
    direccion_fiscal      VARCHAR(255),
    email_facturacion     VARCHAR(150),
    telefono              VARCHAR(50),
    marca_id              SMALLINT NOT NULL REFERENCES marca(id),
    zona_id               SMALLINT NOT NULL REFERENCES zona(id),
    tipo_facturacion      tipo_facturacion NOT NULL DEFAULT 'F1',
    aplica_retencion      BOOLEAN NOT NULL DEFAULT FALSE,  -- bandera rápida; el detalle vive en perfil_tributario_cliente
    municipio             VARCHAR(60),                     -- para reteica territorial
    -- Códigos que Siigo exige por comprobante:
    codigo_vendedor       INT,
    centro_costo          INT,
    subcentro_costo       INT,
    codigo_ciudad         INT,
    codigo_zona_siigo     INT,
    estado                estado_registro NOT NULL DEFAULT 'ACTIVO',
    creado_en             TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cliente_nit ON cliente_corporativo(nit);

-- Qué conceptos aplican a cada corporativo (+ override de % pactado).
CREATE TABLE perfil_tributario_cliente (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_corporativo_id UUID NOT NULL REFERENCES cliente_corporativo(id),
    concepto_tributario_id SMALLINT NOT NULL REFERENCES concepto_tributario(id),
    clasificacion          clasificacion_servicio,  -- define qué vigencia toma (internet/software)
    porcentaje_override    NUMERIC(5,2),            -- % pactado distinto al de la vigencia
    estado                 estado_registro NOT NULL DEFAULT 'ACTIVO',
    UNIQUE (cliente_corporativo_id, concepto_tributario_id)
);
CREATE INDEX idx_perfil_trib_cliente ON perfil_tributario_cliente(cliente_corporativo_id);

-- SEDE / SUSCRIPCIÓN
CREATE TABLE sede_suscripcion (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_corporativo_id UUID NOT NULL REFERENCES cliente_corporativo(id),
    codigo_orden_externo   VARCHAR(50) NOT NULL,
    origen                 origen_orden NOT NULL,
    nic                    VARCHAR(30),                       -- para mayoristas/revendedores
    nombre_sede            VARCHAR(100),
    direccion              VARCHAR(255),
    valor_mensual          NUMERIC(14,2) NOT NULL DEFAULT 0,  -- cargo recurrente vigente
    fecha_certificacion    DATE,                              -- fecha real de cobro
    estado                 estado_registro NOT NULL DEFAULT 'ACTIVO',
    creado_en              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (codigo_orden_externo, origen)
);
CREATE INDEX idx_sede_cliente ON sede_suscripcion(cliente_corporativo_id);
CREATE INDEX idx_sede_nic ON sede_suscripcion(nic) WHERE nic IS NOT NULL;

-- PERIODO DE FACTURACIÓN
CREATE TABLE periodo_facturacion (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo       VARCHAR(7) NOT NULL UNIQUE CHECK (codigo ~ '^\d{4}-\d{2}$'),
    tipo_corte   VARCHAR(20) NOT NULL,   -- ADELANTADO | VENCIDO
    fecha_inicio DATE NOT NULL,
    fecha_fin    DATE NOT NULL,
    estado       VARCHAR(20) NOT NULL DEFAULT 'ABIERTO',   -- ABIERTO | CERRADO
    creado_en    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- NOVEDAD
CREATE TABLE novedad_orden (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sede_suscripcion_id    UUID NOT NULL REFERENCES sede_suscripcion(id),
    tipo_novedad_id        SMALLINT NOT NULL REFERENCES tipo_novedad(id),
    periodo_facturacion_id UUID NOT NULL REFERENCES periodo_facturacion(id),
    valor                  NUMERIC(14,2),   -- monto asociado (nuevo plan, descuento, etc.)
    fecha_efectiva         DATE,            -- para prorrateo
    incluir_en_consolidado BOOLEAN NOT NULL DEFAULT TRUE,
    observacion            VARCHAR(255),
    creado_en              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_novedad_sede_periodo ON novedad_orden(sede_suscripcion_id, periodo_facturacion_id);

-- CUOTA DE INSTALACIÓN (a través de periodos)
CREATE TABLE cuota_instalacion (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sede_suscripcion_id UUID NOT NULL REFERENCES sede_suscripcion(id),
    valor_total         NUMERIC(14,2) NOT NULL,
    numero_cuotas       INT NOT NULL,
    cuotas_facturadas   INT NOT NULL DEFAULT 0,
    valor_cuota         NUMERIC(14,2) NOT NULL,
    estado              estado_cuota NOT NULL DEFAULT 'PENDIENTE',
    creado_en           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cuota_sede ON cuota_instalacion(sede_suscripcion_id);

-- LOTE DE EXPORTACIÓN HACIA Siigo
CREATE TABLE lote_exportacion (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    periodo_facturacion_id UUID NOT NULL REFERENCES periodo_facturacion(id),
    tipo_facturacion       tipo_facturacion NOT NULL,
    tipo_corte             VARCHAR(20) NOT NULL,
    nombre_archivo         VARCHAR(255),
    total_facturas         INT NOT NULL DEFAULT 0,
    estado                 estado_lote NOT NULL DEFAULT 'GENERADO',
    generado_en            TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmado_en          TIMESTAMPTZ
);

-- MAPEO DE CUENTAS CONTABLES (para generar el asiento de doble partida)
CREATE TABLE mapeo_cuenta_contable (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tipo_linea       tipo_linea_asiento NOT NULL,
    tipo_facturacion tipo_facturacion,                    -- NULL = aplica a todas
    tipo_item        tipo_item_facturable,                -- NULL = aplica a todas
    marca_id         SMALLINT REFERENCES marca(id),       -- NULL = aplica a todas
    cuenta_contable  VARCHAR(20) NOT NULL,
    naturaleza       naturaleza_contable NOT NULL,
    estado           estado_registro NOT NULL DEFAULT 'ACTIVO'
);
INSERT INTO mapeo_cuenta_contable (tipo_linea, tipo_facturacion, cuenta_contable, naturaleza) VALUES
    ('INGRESO', 'F1', '4105011501', 'C'),
    ('IVA',     'F1', '2408010515', 'C'),
    ('CARTERA', 'F1', '1305051004', 'D'),
    ('ORDEN',   'F1', '1405011501', 'C');

-- FACTURA
CREATE TABLE factura (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_corporativo_id    UUID NOT NULL REFERENCES cliente_corporativo(id),
    periodo_facturacion_id    UUID NOT NULL REFERENCES periodo_facturacion(id),
    lote_exportacion_id       UUID REFERENCES lote_exportacion(id),
    tipo_facturacion          tipo_facturacion NOT NULL,
    numero_factura            VARCHAR(30),
    cufe                      VARCHAR(96),
    estado_envio              VARCHAR(40),
    ruta_pdf                  VARCHAR(500),
    nit_cliente               VARCHAR(20)  NOT NULL,
    razon_social_cliente      VARCHAR(255) NOT NULL,
    direccion_fiscal_cliente  VARCHAR(255),
    email_facturacion_cliente VARCHAR(150),
    datos_fiscales_snapshot   JSONB,
    valor_bruto               NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_descuento           NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_subtotal            NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_iva                 NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_total               NUMERIC(14,2) NOT NULL DEFAULT 0,  -- VALOR OFICIAL = subtotal + IVA
    -- Cálculo de pago (NO altera el valor oficial ni el asiento):
    valor_retefuente          NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_reteica             NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_reteiva             NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_estampillas         NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_total_retenciones   NUMERIC(14,2) NOT NULL DEFAULT 0,
    neto_a_pagar              NUMERIC(14,2) NOT NULL DEFAULT 0,  -- valor_total - retenciones
    fecha_emision             DATE NOT NULL DEFAULT CURRENT_DATE,
    fecha_vencimiento         DATE,
    forma_pago                VARCHAR(20) DEFAULT 'CREDITO',
    estado                    estado_factura NOT NULL DEFAULT 'BORRADOR',
    anulado_en                TIMESTAMPTZ,
    motivo_anulacion          VARCHAR(255),
    creado_en                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_factura_cliente_periodo_activa
    ON factura(cliente_corporativo_id, periodo_facturacion_id) WHERE estado != 'ANULADA';
CREATE INDEX idx_factura_periodo ON factura(periodo_facturacion_id);
CREATE INDEX idx_factura_estado ON factura(estado);

-- ÍTEM FACTURABLE
CREATE TABLE item_facturable (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factura_id           UUID NOT NULL REFERENCES factura(id) ON DELETE CASCADE,
    tipo_item            tipo_item_facturable NOT NULL,
    sede_suscripcion_id  UUID REFERENCES sede_suscripcion(id),
    descripcion          VARCHAR(255) NOT NULL,
    cantidad             NUMERIC(10,2) NOT NULL DEFAULT 1,
    valor_unitario       NUMERIC(14,2) NOT NULL,
    porcentaje_descuento NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valor_descuento      NUMERIC(14,2) NOT NULL DEFAULT 0,
    base_gravable        NUMERIC(14,2) NOT NULL DEFAULT 0,
    gravado              BOOLEAN NOT NULL DEFAULT TRUE,
    porcentaje_iva       NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valor_iva            NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_total          NUMERIC(14,2) NOT NULL,
    atributos_extra      JSONB,
    creado_en            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_item_factura ON item_facturable(factura_id);

-- NOTAS CRÉDITO / DÉBITO
CREATE TABLE documento_ajuste (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factura_id       UUID NOT NULL REFERENCES factura(id),
    tipo             tipo_documento_ajuste NOT NULL,
    numero_documento VARCHAR(30),
    motivo           VARCHAR(255) NOT NULL,
    valor_bruto      NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_descuento  NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_subtotal   NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_iva        NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_neto       NUMERIC(14,2) NOT NULL DEFAULT 0,
    estado           estado_documento_ajuste NOT NULL DEFAULT 'BORRADOR',
    creado_en        TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_doc_ajuste_factura ON documento_ajuste(factura_id);

CREATE TABLE item_documento_ajuste (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    documento_ajuste_id UUID NOT NULL REFERENCES documento_ajuste(id) ON DELETE CASCADE,
    item_facturable_id  UUID REFERENCES item_facturable(id),
    descripcion         VARCHAR(255) NOT NULL,
    cantidad            NUMERIC(10,2) NOT NULL DEFAULT 1,
    valor_unitario      NUMERIC(14,2) NOT NULL,
    valor_descuento     NUMERIC(14,2) NOT NULL DEFAULT 0,
    base_gravable       NUMERIC(14,2) NOT NULL DEFAULT 0,
    porcentaje_iva      NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valor_iva           NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_total         NUMERIC(14,2) NOT NULL,
    creado_en           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- INGESTA DE ARCHIVOS DE ÓRDENES
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
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    archivo_importado_id UUID NOT NULL REFERENCES archivo_importado(id) ON DELETE CASCADE,
    numero_linea         INT NOT NULL,
    contenido_crudo      JSONB NOT NULL,
    estado_linea         estado_linea_importada NOT NULL DEFAULT 'PENDIENTE',
    mensaje_error        VARCHAR(500),
    sede_suscripcion_id  UUID REFERENCES sede_suscripcion(id),
    novedad_orden_id     UUID REFERENCES novedad_orden(id),
    procesado_en         TIMESTAMPTZ,
    creado_en            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (archivo_importado_id, numero_linea)
);

-- PAGO FACTURA (fuera de alcance v1)
CREATE TABLE pago_factura (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factura_id      UUID NOT NULL REFERENCES factura(id),
    referencia_pago VARCHAR(100) NOT NULL,
    medio_pago      VARCHAR(50)  NOT NULL,
    banco           VARCHAR(50),
    valor           NUMERIC(14,2) NOT NULL,
    fecha_pago      DATE NOT NULL,
    estado          estado_pago NOT NULL DEFAULT 'APLICADO',
    observacion     VARCHAR(255),
    creado_en       TIMESTAMPTZ NOT NULL DEFAULT now()
);
