-- ESQUEMA COMPLETO CORREGIDO - Corporate Billing Service
-- Fecha: 2026-09-28. Estado consolidado de las migraciones V1 a V15.
-- PostgreSQL: validado en 18.6. Codificacion UTF-8.
-- SOLO para una base NUEVA con esquema public vacio. No actualiza bases existentes.
-- Para bases existentes, ejecutar las migraciones de Flyway del proyecto.
-- No contiene DROP, datos de clientes ni credenciales. Incluye catalogos iniciales.
-- Conserva tablas heredadas por compatibilidad; pagos no forman parte del alcance
-- de recepcion de facturas. bkp_perfil_tributario_cliente queda vacia, como en V4.
-- No crea flyway_schema_history. Leer REVISION_SCHEMAS.md antes de usar con Spring.
-- Ejecutar el archivo completo; ante un error, hacer ROLLBACK.

BEGIN;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_class c
               JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public' AND c.relkind IN ('r','p','v','m','S','f')) THEN
        RAISE EXCEPTION 'Se requiere una base nueva con public vacio; use Flyway para actualizar una base existente';
    END IF;
END $$;

--
-- PostgreSQL database dump
--


-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;
SET LOCAL idle_in_transaction_session_timeout = 0;
SET LOCAL client_encoding = 'UTF8';
SET LOCAL standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', true);
SET LOCAL check_function_bodies = false;
SET LOCAL xmloption = content;
SET LOCAL client_min_messages = warning;
SET LOCAL row_security = off;

--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- Name: clasificacion_servicio; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.clasificacion_servicio AS ENUM (
    'INTERNET',
    'SOFTWARE'
);


--
-- Name: estado_archivo; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.estado_archivo AS ENUM (
    'RECIBIDO',
    'PROCESANDO',
    'PROCESADO',
    'ERROR'
);


--
-- Name: estado_cuota; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.estado_cuota AS ENUM (
    'PENDIENTE',
    'FACTURANDO',
    'PAGADA',
    'ANULADA'
);


--
-- Name: estado_documento_ajuste; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.estado_documento_ajuste AS ENUM (
    'BORRADOR',
    'APLICADO',
    'ANULADO'
);


--
-- Name: estado_factura; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.estado_factura AS ENUM (
    'BORRADOR',
    'CONSOLIDADA',
    'PAGADA',
    'ANULADA',
    'REEMPLAZADA'
);


--
-- Name: estado_linea_importada; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.estado_linea_importada AS ENUM (
    'PENDIENTE',
    'PROCESADA',
    'ERROR'
);


--
-- Name: estado_lote; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.estado_lote AS ENUM (
    'GENERADO',
    'ENVIADO',
    'CONFIRMADO',
    'ERROR'
);


--
-- Name: estado_pago; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.estado_pago AS ENUM (
    'APLICADO',
    'ANULADO'
);


--
-- Name: estado_registro; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.estado_registro AS ENUM (
    'ACTIVO',
    'INACTIVO'
);


--
-- Name: naturaleza_contable; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.naturaleza_contable AS ENUM (
    'D',
    'C'
);


--
-- Name: origen_orden; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.origen_orden AS ENUM (
    'SPE',
    'MEGA_RED',
    'ENLACES'
);


--
-- Name: tipo_documento_ajuste; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.tipo_documento_ajuste AS ENUM (
    'NOTA_CREDITO',
    'NOTA_DEBITO'
);


--
-- Name: tipo_facturacion; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.tipo_facturacion AS ENUM (
    'F1',
    'F20'
);


--
-- Name: tipo_item_facturable; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.tipo_item_facturable AS ENUM (
    'SERVICIO_CORPORATIVO',
    'SVA_STREAMING',
    'SVA_PRODUCTO'
);


--
-- Name: tipo_linea_asiento; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.tipo_linea_asiento AS ENUM (
    'INGRESO',
    'IVA',
    'CARTERA',
    'ORDEN'
);


SET LOCAL default_tablespace = '';

SET LOCAL default_table_access_method = heap;

--
-- Name: archivo_importado; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.archivo_importado (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    nombre_archivo character varying(255) NOT NULL,
    origen public.origen_orden NOT NULL,
    estado public.estado_archivo DEFAULT 'RECIBIDO'::public.estado_archivo NOT NULL,
    total_registros integer,
    registros_con_error integer DEFAULT 0,
    recibido_en timestamp with time zone DEFAULT now() NOT NULL,
    procesado_en timestamp with time zone,
    metodo_importacion character varying(50) DEFAULT 'EXCEL'::character varying
);


--
-- Name: archivo_importado_linea; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.archivo_importado_linea (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    archivo_importado_id uuid NOT NULL,
    numero_linea integer NOT NULL,
    contenido_crudo jsonb NOT NULL,
    estado_linea public.estado_linea_importada DEFAULT 'PENDIENTE'::public.estado_linea_importada NOT NULL,
    mensaje_error character varying(500),
    sede_suscripcion_id uuid,
    novedad_orden_id uuid,
    procesado_en timestamp with time zone,
    creado_en timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: bkp_perfil_tributario_cliente; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bkp_perfil_tributario_cliente (
    id uuid,
    cliente_corporativo_id uuid,
    concepto_tributario_id smallint,
    clasificacion public.clasificacion_servicio,
    porcentaje_override numeric(5,2),
    estado public.estado_registro,
    creado_por character varying(100)
);


--
-- Name: campo_extra_factura; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.campo_extra_factura (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    factura_id uuid NOT NULL,
    definicion_campo_extra_id uuid NOT NULL,
    valor text NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: cartera_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cartera_snapshot (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    nombre_archivo character varying(255) NOT NULL,
    ruta_archivo character varying(500),
    hash_sha256 character varying(64),
    tamano_bytes bigint,
    generado_en timestamp with time zone NOT NULL,
    filas_totales integer DEFAULT 0 NOT NULL,
    filas_emparejadas integer DEFAULT 0 NOT NULL,
    filas_sin_factura integer DEFAULT 0 NOT NULL,
    filas_con_error integer DEFAULT 0 NOT NULL,
    facturas_sin_corte integer DEFAULT 0 NOT NULL,
    estado character varying(20) DEFAULT 'PROCESANDO'::character varying NOT NULL,
    errores text,
    ingerido_en timestamp with time zone DEFAULT now() NOT NULL,
    ingerido_por character varying(100)
);


--
-- Name: COLUMN cartera_snapshot.hash_sha256; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cartera_snapshot.hash_sha256 IS 'Permite saltar un archivo ya ingerido: el lector de la carpeta compartida no mueve ni marca los archivos procesados, así que el mismo corte reaparece en cada ciclo.';


--
-- Name: COLUMN cartera_snapshot.facturas_sin_corte; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cartera_snapshot.facturas_sin_corte IS 'Facturas vigentes del sistema que NO aparecieron en el corte. Ver la política siigo.cartera.tratar-ausencia-como-saldo-cero antes de interpretarlo como pagadas.';


--
-- Name: cartera_snapshot_linea; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cartera_snapshot_linea (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cartera_snapshot_id uuid NOT NULL,
    numero_fila integer NOT NULL,
    nit_archivo character varying(20),
    cuenta_contable character varying(20),
    tipo_comprobante character varying(5),
    codigo_comprobante integer,
    numero_documento character varying(30),
    tipo_facturacion public.tipo_facturacion,
    saldo numeric(14,2),
    saldo_calculado numeric(14,2),
    diferencia numeric(14,2),
    fecha_vencimiento date,
    fecha_documento date,
    centro_costo character varying(20),
    codigo_ciudad character varying(20),
    codigo_zona character varying(20),
    factura_id uuid,
    estado_linea character varying(30) DEFAULT 'PENDIENTE'::character varying NOT NULL,
    mensaje character varying(500)
);


--
-- Name: COLUMN cartera_snapshot_linea.estado_linea; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cartera_snapshot_linea.estado_linea IS 'APLICADA | SIN_FACTURA | NIT_DISCREPANTE | CORTE_OBSOLETO | ERROR_FORMATO';


--
-- Name: cliente_corporativo; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cliente_corporativo (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    nit character varying(20) NOT NULL,
    razon_social character varying(255) NOT NULL,
    direccion_fiscal character varying(255),
    email_facturacion character varying(150),
    telefono character varying(50),
    marca_id smallint NOT NULL,
    zona_id smallint NOT NULL,
    tipo_facturacion public.tipo_facturacion DEFAULT 'F1'::public.tipo_facturacion NOT NULL,
    aplica_retencion boolean DEFAULT false NOT NULL,
    municipio character varying(60),
    codigo_vendedor integer,
    centro_costo integer,
    subcentro_costo integer,
    codigo_ciudad integer,
    codigo_zona_siigo integer,
    estado public.estado_registro DEFAULT 'ACTIVO'::public.estado_registro NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    actualizado_en timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: concepto_tributario; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.concepto_tributario (
    id smallint NOT NULL,
    codigo character varying(20) NOT NULL,
    nombre character varying(80) NOT NULL,
    territorial boolean DEFAULT false NOT NULL
);


--
-- Name: cuota_instalacion; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cuota_instalacion (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    sede_suscripcion_id uuid NOT NULL,
    valor_total numeric(14,2) NOT NULL,
    numero_cuotas integer NOT NULL,
    cuotas_facturadas integer DEFAULT 0 NOT NULL,
    valor_cuota numeric(14,2) NOT NULL,
    estado public.estado_cuota DEFAULT 'PENDIENTE'::public.estado_cuota NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: definicion_campo_extra; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.definicion_campo_extra (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    clave_campo character varying(50) NOT NULL,
    etiqueta character varying(100) NOT NULL,
    tipo_dato character varying(20) DEFAULT 'TEXTO'::character varying NOT NULL,
    obligatorio boolean DEFAULT false NOT NULL,
    estado public.estado_registro DEFAULT 'ACTIVO'::public.estado_registro NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: documento_ajuste; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.documento_ajuste (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    factura_id uuid NOT NULL,
    tipo public.tipo_documento_ajuste NOT NULL,
    numero_documento character varying(30),
    motivo character varying(255) NOT NULL,
    valor_bruto numeric(14,2) DEFAULT 0 NOT NULL,
    valor_descuento numeric(14,2) DEFAULT 0 NOT NULL,
    valor_subtotal numeric(14,2) DEFAULT 0 NOT NULL,
    valor_iva numeric(14,2) DEFAULT 0 NOT NULL,
    valor_neto numeric(14,2) DEFAULT 0 NOT NULL,
    estado public.estado_documento_ajuste DEFAULT 'BORRADOR'::public.estado_documento_ajuste NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    actualizado_en timestamp with time zone DEFAULT now() NOT NULL,
    creado_por character varying(100)
);


--
-- Name: estado_sincronizacion; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.estado_sincronizacion (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    iniciado_en timestamp with time zone DEFAULT now() NOT NULL,
    finalizado_en timestamp with time zone,
    duracion_ms bigint,
    tipo_ejecucion character varying(20) DEFAULT 'PROGRAMADA'::character varying NOT NULL,
    lotes_confirmados integer DEFAULT 0,
    facturas_detectadas integer DEFAULT 0,
    pdfs_clasificados integer DEFAULT 0,
    errores text,
    estado character varying(20) DEFAULT 'EN_PROGRESO'::character varying NOT NULL
);


--
-- Name: factura; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.factura (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cliente_corporativo_id uuid NOT NULL,
    periodo_facturacion_id uuid NOT NULL,
    lote_exportacion_id uuid,
    tipo_facturacion public.tipo_facturacion NOT NULL,
    numero_factura character varying(30),
    cufe character varying(96),
    estado_envio character varying(40),
    ruta_pdf character varying(500),
    nit_cliente character varying(20) NOT NULL,
    razon_social_cliente character varying(255) NOT NULL,
    direccion_fiscal_cliente character varying(255),
    email_facturacion_cliente character varying(150),
    datos_fiscales_snapshot jsonb,
    valor_bruto numeric(14,2) DEFAULT 0 NOT NULL,
    valor_descuento numeric(14,2) DEFAULT 0 NOT NULL,
    valor_subtotal numeric(14,2) DEFAULT 0 NOT NULL,
    valor_iva numeric(14,2) DEFAULT 0 NOT NULL,
    valor_total numeric(14,2) DEFAULT 0 NOT NULL,
    valor_retefuente numeric(14,2) DEFAULT 0 NOT NULL,
    valor_reteica numeric(14,2) DEFAULT 0 NOT NULL,
    valor_reteiva numeric(14,2) DEFAULT 0 NOT NULL,
    valor_estampillas numeric(14,2) DEFAULT 0 NOT NULL,
    valor_total_retenciones numeric(14,2) DEFAULT 0 NOT NULL,
    neto_a_pagar numeric(14,2) DEFAULT 0 NOT NULL,
    fecha_emision date DEFAULT CURRENT_DATE NOT NULL,
    fecha_vencimiento date,
    forma_pago character varying(20) DEFAULT 'CREDITO'::character varying,
    estado public.estado_factura DEFAULT 'BORRADOR'::public.estado_factura NOT NULL,
    anulado_en timestamp with time zone,
    motivo_anulacion character varying(255),
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    actualizado_en timestamp with time zone DEFAULT now() NOT NULL,
    creado_por character varying(100),
    anulado_por character varying(100),
    es_residencial boolean DEFAULT false NOT NULL,
    numero_version integer DEFAULT 1 NOT NULL,
    factura_origen_id uuid NOT NULL,
    factura_anterior_id uuid,
    reemplazada_por_id uuid,
    reemplazada_en timestamp with time zone,
    motivo_regeneracion character varying(255),
    regenerado_por character varying(100),
    saldo_siigo numeric(14,2),
    saldo_siigo_actualizado_en timestamp with time zone,
    saldo_siigo_snapshot_id uuid,
    saldo_siigo_corte_en timestamp with time zone,
    origen_documento character varying(20) DEFAULT 'INTERNO'::character varying NOT NULL,
    clave_documento_externo character varying(80),
    CONSTRAINT chk_factura_cadena_coherente CHECK ((((numero_version = 1) AND (factura_anterior_id IS NULL)) OR ((numero_version > 1) AND (factura_anterior_id IS NOT NULL)))),
    CONSTRAINT chk_factura_no_autoreemplazo CHECK (((reemplazada_por_id IS NULL) OR (reemplazada_por_id <> id))),
    CONSTRAINT chk_factura_version_positiva CHECK ((numero_version >= 1))
);


--
-- Name: COLUMN factura.numero_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.factura.numero_version IS 'Versión dentro de la cadena de refacturación. La primera emisión es 1.';


--
-- Name: COLUMN factura.factura_origen_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.factura.factura_origen_id IS 'Raíz de la cadena de versiones. En la versión 1 apunta a sí misma.';


--
-- Name: COLUMN factura.factura_anterior_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.factura.factura_anterior_id IS 'Versión inmediatamente anterior (NULL en la versión 1).';


--
-- Name: COLUMN factura.reemplazada_por_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.factura.reemplazada_por_id IS 'Versión que sustituyó a esta factura (NULL si es la vigente).';


--
-- Name: COLUMN factura.motivo_regeneracion; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.factura.motivo_regeneracion IS 'Motivo por el cual esta versión fue generada.';


--
-- Name: COLUMN factura.saldo_siigo; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.factura.saldo_siigo IS 'Saldo pendiente según el corte de cartera de Siigo. FUENTE DE VERDAD. NULL = nunca se ha recibido un corte para esta factura, en cuyo caso rige el saldo calculado localmente.';


--
-- Name: COLUMN factura.saldo_siigo_corte_en; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.factura.saldo_siigo_corte_en IS 'Fecha de generación del corte que produjo este saldo. Se usa para no dejar que un archivo viejo sobrescriba un movimiento local más reciente.';


--
-- Name: factura_pdf_historico; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.factura_pdf_historico (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    factura_id uuid NOT NULL,
    numero_version integer NOT NULL,
    ruta_pdf character varying(500) NOT NULL,
    nombre_archivo character varying(255),
    hash_sha256 character varying(64),
    tamano_bytes bigint,
    numero_factura character varying(30),
    cufe character varying(96),
    origen character varying(40) DEFAULT 'CARPETA_COMPARTIDA_SIIGO'::character varying NOT NULL,
    motivo character varying(255),
    archivado_en timestamp with time zone DEFAULT now() NOT NULL,
    archivado_por character varying(100)
);


--
-- Name: historial_recepcion_archivo; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.historial_recepcion_archivo (
    id uuid NOT NULL,
    procesado_en timestamp with time zone NOT NULL,
    archivo character varying(255) NOT NULL,
    origen character varying(20) NOT NULL,
    estado character varying(20) NOT NULL,
    sha256 character varying(64),
    nuevos integer DEFAULT 0 NOT NULL,
    repetidos integer DEFAULT 0 NOT NULL,
    detalle character varying(1500),
    usuario character varying(255)
);


--
-- Name: item_documento_ajuste; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.item_documento_ajuste (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    documento_ajuste_id uuid NOT NULL,
    item_facturable_id uuid,
    descripcion character varying(255) NOT NULL,
    cantidad numeric(10,2) DEFAULT 1 NOT NULL,
    valor_unitario numeric(14,2) NOT NULL,
    valor_descuento numeric(14,2) DEFAULT 0 NOT NULL,
    base_gravable numeric(14,2) DEFAULT 0 NOT NULL,
    porcentaje_iva numeric(5,2) DEFAULT 0 NOT NULL,
    valor_iva numeric(14,2) DEFAULT 0 NOT NULL,
    valor_total numeric(14,2) NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: item_facturable; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.item_facturable (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    factura_id uuid NOT NULL,
    tipo_item public.tipo_item_facturable NOT NULL,
    sede_suscripcion_id uuid,
    descripcion character varying(255) NOT NULL,
    cantidad numeric(10,2) DEFAULT 1 NOT NULL,
    valor_unitario numeric(14,2) NOT NULL,
    porcentaje_descuento numeric(5,2) DEFAULT 0 NOT NULL,
    valor_descuento numeric(14,2) DEFAULT 0 NOT NULL,
    base_gravable numeric(14,2) DEFAULT 0 NOT NULL,
    gravado boolean DEFAULT true NOT NULL,
    porcentaje_iva numeric(5,2) DEFAULT 0 NOT NULL,
    valor_iva numeric(14,2) DEFAULT 0 NOT NULL,
    valor_total numeric(14,2) NOT NULL,
    atributos_extra jsonb,
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    valor_mensual_base numeric(14,2),
    codigo_producto character varying(80)
);


--
-- Name: COLUMN item_facturable.valor_mensual_base; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.item_facturable.valor_mensual_base IS 'Tarifa mensual completa que originó la línea, antes de prorratear. Permite reconstruir la tarifa vigente al inicio del periodo al regenerar la factura.';


--
-- Name: lote_exportacion; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.lote_exportacion (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    periodo_facturacion_id uuid NOT NULL,
    tipo_facturacion public.tipo_facturacion NOT NULL,
    tipo_corte character varying(20) NOT NULL,
    nombre_archivo character varying(255),
    total_facturas integer DEFAULT 0 NOT NULL,
    estado public.estado_lote DEFAULT 'GENERADO'::public.estado_lote NOT NULL,
    generado_en timestamp with time zone DEFAULT now() NOT NULL,
    confirmado_en timestamp with time zone,
    generado_por character varying(100),
    confirmado_por character varying(100)
);


--
-- Name: mapeo_cuenta_contable; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mapeo_cuenta_contable (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tipo_linea public.tipo_linea_asiento NOT NULL,
    tipo_facturacion public.tipo_facturacion,
    tipo_item public.tipo_item_facturable,
    marca_id smallint,
    cuenta_contable character varying(20) NOT NULL,
    naturaleza public.naturaleza_contable NOT NULL,
    estado public.estado_registro DEFAULT 'ACTIVO'::public.estado_registro NOT NULL
);


--
-- Name: marca; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.marca (
    id smallint NOT NULL,
    nombre character varying(50) NOT NULL
);


--
-- Name: novedad_orden; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.novedad_orden (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    sede_suscripcion_id uuid NOT NULL,
    tipo_novedad_id smallint NOT NULL,
    periodo_facturacion_id uuid NOT NULL,
    valor numeric(14,2),
    fecha_efectiva date,
    incluir_en_consolidado boolean DEFAULT true NOT NULL,
    observacion character varying(255),
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    factura_id uuid,
    facturado_en timestamp with time zone
);


--
-- Name: COLUMN novedad_orden.factura_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.novedad_orden.factura_id IS 'Versión de factura vigente que consumió esta novedad.';


--
-- Name: pago; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pago (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cliente_corporativo_id uuid NOT NULL,
    fecha_pago date NOT NULL,
    medio_pago character varying(50) NOT NULL,
    cuenta_banco character varying(50) NOT NULL,
    valor_bruto numeric(14,2) NOT NULL,
    total_retenciones numeric(14,2) DEFAULT 0 NOT NULL,
    valor_redondeo numeric(14,2) DEFAULT 0 NOT NULL,
    neto_pagado numeric(14,2) NOT NULL,
    numero_transaccion character varying(100),
    observacion character varying(255),
    usuario_creacion character varying(100),
    estado character varying(20) DEFAULT 'APLICADO'::character varying NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    clave_idempotencia character varying(128),
    huella_solicitud character varying(64)
);


--
-- Name: pago_factura; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pago_factura (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    pago_id uuid NOT NULL,
    factura_id uuid NOT NULL,
    valor_aplicado numeric(14,2) NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: periodo_facturacion; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.periodo_facturacion (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    codigo character varying(7) NOT NULL,
    tipo_corte character varying(20) NOT NULL,
    fecha_inicio date NOT NULL,
    fecha_fin date NOT NULL,
    estado character varying(20) DEFAULT 'ABIERTO'::character varying NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT periodo_facturacion_codigo_check CHECK (((codigo)::text ~ '^\d{4}-\d{2}$'::text))
);


--
-- Name: retencion_pago; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.retencion_pago (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    pago_id uuid NOT NULL,
    vigencia_concepto_id uuid,
    numero_version integer DEFAULT 1 NOT NULL,
    concepto_codigo character varying(20) NOT NULL,
    nombre_concepto character varying(100) NOT NULL,
    porcentaje numeric(9,6) NOT NULL,
    base_minima numeric(14,2) NOT NULL,
    base_calculo numeric(14,2) NOT NULL,
    valor_retenido numeric(14,2) NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    motivo_ajuste character varying(255)
);


--
-- Name: sede_suscripcion; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sede_suscripcion (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cliente_corporativo_id uuid NOT NULL,
    codigo_orden_externo character varying(50) NOT NULL,
    origen public.origen_orden NOT NULL,
    nic character varying(30),
    nombre_sede character varying(100),
    direccion character varying(255),
    valor_mensual numeric(14,2) DEFAULT 0 NOT NULL,
    fecha_certificacion date,
    estado public.estado_registro DEFAULT 'ACTIVO'::public.estado_registro NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    es_residencial boolean DEFAULT false NOT NULL
);


--
-- Name: tipo_novedad; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tipo_novedad (
    id smallint NOT NULL,
    nombre character varying(50) NOT NULL
);


--
-- Name: vigencia_concepto; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vigencia_concepto (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    concepto_tributario_id smallint NOT NULL,
    clasificacion public.clasificacion_servicio,
    municipio character varying(60),
    base_minima numeric(14,2) DEFAULT 0 NOT NULL,
    porcentaje numeric(5,2) NOT NULL,
    vigente_desde date NOT NULL,
    vigente_hasta date,
    estado public.estado_registro DEFAULT 'ACTIVO'::public.estado_registro NOT NULL,
    creado_por character varying(100),
    actualizado_por character varying(100),
    nombre character varying(100),
    numero_version integer DEFAULT 1 NOT NULL
);


--
-- Name: zona; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.zona (
    id smallint NOT NULL,
    nombre character varying(50) NOT NULL
);


--
-- Data for Name: archivo_importado; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: archivo_importado_linea; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: bkp_perfil_tributario_cliente; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: campo_extra_factura; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: cartera_snapshot; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: cartera_snapshot_linea; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: cliente_corporativo; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: concepto_tributario; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.concepto_tributario VALUES (1, 'RETEFUENTE', 'Retención en la fuente', false);
INSERT INTO public.concepto_tributario VALUES (2, 'RETEICA', 'Retención de ICA', true);
INSERT INTO public.concepto_tributario VALUES (3, 'RETEIVA', 'Retención de IVA', false);
INSERT INTO public.concepto_tributario VALUES (4, 'ESTAMPILLA', 'Estampillas', true);


--
-- Data for Name: cuota_instalacion; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: definicion_campo_extra; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: documento_ajuste; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: estado_sincronizacion; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: factura; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: factura_pdf_historico; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: historial_recepcion_archivo; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: item_documento_ajuste; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: item_facturable; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: lote_exportacion; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: mapeo_cuenta_contable; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.mapeo_cuenta_contable VALUES ('c827ef5f-3d65-445d-b254-ecfa11408a9f', 'INGRESO', 'F1', NULL, NULL, '4105011501', 'C', 'ACTIVO');
INSERT INTO public.mapeo_cuenta_contable VALUES ('e94d4cbd-bfb2-45cd-91b2-b143f8da730f', 'IVA', 'F1', NULL, NULL, '2408010515', 'C', 'ACTIVO');
INSERT INTO public.mapeo_cuenta_contable VALUES ('68f444bf-74dc-441d-9cf4-9574920d8470', 'CARTERA', 'F1', NULL, NULL, '1305051004', 'D', 'ACTIVO');
INSERT INTO public.mapeo_cuenta_contable VALUES ('11a96a99-ba1b-4c1e-afb0-c57f2a950de1', 'ORDEN', 'F1', NULL, NULL, '1405011501', 'C', 'ACTIVO');


--
-- Data for Name: marca; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.marca VALUES (1, 'Sistemas Palacios');
INSERT INTO public.marca VALUES (2, 'Meganet');
INSERT INTO public.marca VALUES (3, 'Enlaces');


--
-- Data for Name: novedad_orden; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: pago; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: pago_factura; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: periodo_facturacion; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: retencion_pago; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: sede_suscripcion; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: tipo_novedad; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.tipo_novedad VALUES (1, 'ALTA');
INSERT INTO public.tipo_novedad VALUES (2, 'BAJA');
INSERT INTO public.tipo_novedad VALUES (3, 'CAMBIO_PLAN');
INSERT INTO public.tipo_novedad VALUES (4, 'CAMBIO_SERVICIO');
INSERT INTO public.tipo_novedad VALUES (5, 'SUSPENSION');
INSERT INTO public.tipo_novedad VALUES (6, 'REACTIVACION');
INSERT INTO public.tipo_novedad VALUES (7, 'TRASLADO_DIRECCION');


--
-- Data for Name: vigencia_concepto; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: zona; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.zona VALUES (1, 'Pasto');
INSERT INTO public.zona VALUES (2, 'Norte');
INSERT INTO public.zona VALUES (3, 'Sur');
INSERT INTO public.zona VALUES (4, 'Occidente');
INSERT INTO public.zona VALUES (5, 'Costa');


--
-- Name: archivo_importado_linea archivo_importado_linea_archivo_importado_id_numero_linea_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.archivo_importado_linea
    ADD CONSTRAINT archivo_importado_linea_archivo_importado_id_numero_linea_key UNIQUE (archivo_importado_id, numero_linea);


--
-- Name: archivo_importado_linea archivo_importado_linea_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.archivo_importado_linea
    ADD CONSTRAINT archivo_importado_linea_pkey PRIMARY KEY (id);


--
-- Name: archivo_importado archivo_importado_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.archivo_importado
    ADD CONSTRAINT archivo_importado_pkey PRIMARY KEY (id);


--
-- Name: campo_extra_factura campo_extra_factura_factura_id_definicion_campo_extra_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campo_extra_factura
    ADD CONSTRAINT campo_extra_factura_factura_id_definicion_campo_extra_id_key UNIQUE (factura_id, definicion_campo_extra_id);


--
-- Name: campo_extra_factura campo_extra_factura_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campo_extra_factura
    ADD CONSTRAINT campo_extra_factura_pkey PRIMARY KEY (id);


--
-- Name: cartera_snapshot_linea cartera_snapshot_linea_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cartera_snapshot_linea
    ADD CONSTRAINT cartera_snapshot_linea_pkey PRIMARY KEY (id);


--
-- Name: cartera_snapshot cartera_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cartera_snapshot
    ADD CONSTRAINT cartera_snapshot_pkey PRIMARY KEY (id);


--
-- Name: cliente_corporativo cliente_corporativo_nit_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cliente_corporativo
    ADD CONSTRAINT cliente_corporativo_nit_key UNIQUE (nit);


--
-- Name: cliente_corporativo cliente_corporativo_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cliente_corporativo
    ADD CONSTRAINT cliente_corporativo_pkey PRIMARY KEY (id);


--
-- Name: concepto_tributario concepto_tributario_codigo_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.concepto_tributario
    ADD CONSTRAINT concepto_tributario_codigo_key UNIQUE (codigo);


--
-- Name: concepto_tributario concepto_tributario_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.concepto_tributario
    ADD CONSTRAINT concepto_tributario_pkey PRIMARY KEY (id);


--
-- Name: cuota_instalacion cuota_instalacion_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cuota_instalacion
    ADD CONSTRAINT cuota_instalacion_pkey PRIMARY KEY (id);


--
-- Name: definicion_campo_extra definicion_campo_extra_clave_campo_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.definicion_campo_extra
    ADD CONSTRAINT definicion_campo_extra_clave_campo_key UNIQUE (clave_campo);


--
-- Name: definicion_campo_extra definicion_campo_extra_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.definicion_campo_extra
    ADD CONSTRAINT definicion_campo_extra_pkey PRIMARY KEY (id);


--
-- Name: documento_ajuste documento_ajuste_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documento_ajuste
    ADD CONSTRAINT documento_ajuste_pkey PRIMARY KEY (id);


--
-- Name: estado_sincronizacion estado_sincronizacion_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.estado_sincronizacion
    ADD CONSTRAINT estado_sincronizacion_pkey PRIMARY KEY (id);


--
-- Name: factura_pdf_historico factura_pdf_historico_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.factura_pdf_historico
    ADD CONSTRAINT factura_pdf_historico_pkey PRIMARY KEY (id);


--
-- Name: factura factura_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.factura
    ADD CONSTRAINT factura_pkey PRIMARY KEY (id);


--
-- Name: historial_recepcion_archivo historial_recepcion_archivo_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.historial_recepcion_archivo
    ADD CONSTRAINT historial_recepcion_archivo_pkey PRIMARY KEY (id);


--
-- Name: item_documento_ajuste item_documento_ajuste_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item_documento_ajuste
    ADD CONSTRAINT item_documento_ajuste_pkey PRIMARY KEY (id);


--
-- Name: item_facturable item_facturable_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item_facturable
    ADD CONSTRAINT item_facturable_pkey PRIMARY KEY (id);


--
-- Name: lote_exportacion lote_exportacion_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lote_exportacion
    ADD CONSTRAINT lote_exportacion_pkey PRIMARY KEY (id);


--
-- Name: mapeo_cuenta_contable mapeo_cuenta_contable_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mapeo_cuenta_contable
    ADD CONSTRAINT mapeo_cuenta_contable_pkey PRIMARY KEY (id);


--
-- Name: marca marca_nombre_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.marca
    ADD CONSTRAINT marca_nombre_key UNIQUE (nombre);


--
-- Name: marca marca_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.marca
    ADD CONSTRAINT marca_pkey PRIMARY KEY (id);


--
-- Name: novedad_orden novedad_orden_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.novedad_orden
    ADD CONSTRAINT novedad_orden_pkey PRIMARY KEY (id);


--
-- Name: pago_factura pago_factura_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pago_factura
    ADD CONSTRAINT pago_factura_pkey PRIMARY KEY (id);


--
-- Name: pago pago_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pago
    ADD CONSTRAINT pago_pkey PRIMARY KEY (id);


--
-- Name: periodo_facturacion periodo_facturacion_codigo_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.periodo_facturacion
    ADD CONSTRAINT periodo_facturacion_codigo_key UNIQUE (codigo);


--
-- Name: periodo_facturacion periodo_facturacion_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.periodo_facturacion
    ADD CONSTRAINT periodo_facturacion_pkey PRIMARY KEY (id);


--
-- Name: retencion_pago retencion_pago_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retencion_pago
    ADD CONSTRAINT retencion_pago_pkey PRIMARY KEY (id);


--
-- Name: sede_suscripcion sede_suscripcion_codigo_orden_externo_origen_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sede_suscripcion
    ADD CONSTRAINT sede_suscripcion_codigo_orden_externo_origen_key UNIQUE (codigo_orden_externo, origen);


--
-- Name: sede_suscripcion sede_suscripcion_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sede_suscripcion
    ADD CONSTRAINT sede_suscripcion_pkey PRIMARY KEY (id);


--
-- Name: tipo_novedad tipo_novedad_nombre_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tipo_novedad
    ADD CONSTRAINT tipo_novedad_nombre_key UNIQUE (nombre);


--
-- Name: tipo_novedad tipo_novedad_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tipo_novedad
    ADD CONSTRAINT tipo_novedad_pkey PRIMARY KEY (id);


--
-- Name: cartera_snapshot_linea uq_cartera_linea_fila; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cartera_snapshot_linea
    ADD CONSTRAINT uq_cartera_linea_fila UNIQUE (cartera_snapshot_id, numero_fila);


--
-- Name: vigencia_concepto vigencia_concepto_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vigencia_concepto
    ADD CONSTRAINT vigencia_concepto_pkey PRIMARY KEY (id);


--
-- Name: zona zona_nombre_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.zona
    ADD CONSTRAINT zona_nombre_key UNIQUE (nombre);


--
-- Name: zona zona_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.zona
    ADD CONSTRAINT zona_pkey PRIMARY KEY (id);


--
-- Name: idx_campo_extra_factura_factura; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_campo_extra_factura_factura ON public.campo_extra_factura USING btree (factura_id);


--
-- Name: idx_cartera_linea_discrepancia; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cartera_linea_discrepancia ON public.cartera_snapshot_linea USING btree (cartera_snapshot_id) WHERE ((diferencia IS NOT NULL) AND (diferencia <> (0)::numeric));


--
-- Name: idx_cartera_linea_documento; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cartera_linea_documento ON public.cartera_snapshot_linea USING btree (codigo_comprobante, numero_documento);


--
-- Name: idx_cartera_linea_factura; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cartera_linea_factura ON public.cartera_snapshot_linea USING btree (factura_id) WHERE (factura_id IS NOT NULL);


--
-- Name: idx_cartera_linea_no_aplicada; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cartera_linea_no_aplicada ON public.cartera_snapshot_linea USING btree (cartera_snapshot_id) WHERE ((estado_linea)::text <> 'APLICADA'::text);


--
-- Name: idx_cartera_snapshot_generado; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cartera_snapshot_generado ON public.cartera_snapshot USING btree (generado_en DESC);


--
-- Name: idx_cliente_nit; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cliente_nit ON public.cliente_corporativo USING btree (nit);


--
-- Name: idx_cuota_sede; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cuota_sede ON public.cuota_instalacion USING btree (sede_suscripcion_id);


--
-- Name: idx_doc_ajuste_factura; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_ajuste_factura ON public.documento_ajuste USING btree (factura_id);


--
-- Name: idx_estado_sincronizacion_iniciado; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_estado_sincronizacion_iniciado ON public.estado_sincronizacion USING btree (iniciado_en DESC);


--
-- Name: idx_factura_estado; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_factura_estado ON public.factura USING btree (estado);


--
-- Name: idx_factura_origen; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_factura_origen ON public.factura USING btree (factura_origen_id);


--
-- Name: idx_factura_pdf_historico_factura; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_factura_pdf_historico_factura ON public.factura_pdf_historico USING btree (factura_id, archivado_en DESC);


--
-- Name: idx_factura_pdf_historico_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_factura_pdf_historico_hash ON public.factura_pdf_historico USING btree (hash_sha256) WHERE (hash_sha256 IS NOT NULL);


--
-- Name: idx_factura_periodo; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_factura_periodo ON public.factura USING btree (periodo_facturacion_id);


--
-- Name: idx_factura_reemplazada_por; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_factura_reemplazada_por ON public.factura USING btree (reemplazada_por_id) WHERE (reemplazada_por_id IS NOT NULL);


--
-- Name: idx_factura_saldo_siigo; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_factura_saldo_siigo ON public.factura USING btree (saldo_siigo) WHERE (saldo_siigo IS NOT NULL);


--
-- Name: idx_historial_recepcion_fecha; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_historial_recepcion_fecha ON public.historial_recepcion_archivo USING btree (procesado_en DESC);


--
-- Name: idx_item_factura; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_item_factura ON public.item_facturable USING btree (factura_id);


--
-- Name: idx_item_facturable_sede; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_item_facturable_sede ON public.item_facturable USING btree (sede_suscripcion_id) WHERE (sede_suscripcion_id IS NOT NULL);


--
-- Name: idx_novedad_factura; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_novedad_factura ON public.novedad_orden USING btree (factura_id) WHERE (factura_id IS NOT NULL);


--
-- Name: idx_novedad_periodo_pendiente; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_novedad_periodo_pendiente ON public.novedad_orden USING btree (periodo_facturacion_id) WHERE (factura_id IS NULL);


--
-- Name: idx_novedad_sede_periodo; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_novedad_sede_periodo ON public.novedad_orden USING btree (sede_suscripcion_id, periodo_facturacion_id);


--
-- Name: idx_pago_cliente; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pago_cliente ON public.pago USING btree (cliente_corporativo_id);


--
-- Name: idx_pago_factura_factura; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pago_factura_factura ON public.pago_factura USING btree (factura_id);


--
-- Name: idx_pago_factura_pago; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pago_factura_pago ON public.pago_factura USING btree (pago_id);


--
-- Name: idx_pago_fecha; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pago_fecha ON public.pago USING btree (fecha_pago);


--
-- Name: idx_retencion_pago_pago; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_retencion_pago_pago ON public.retencion_pago USING btree (pago_id);


--
-- Name: idx_sede_cliente; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sede_cliente ON public.sede_suscripcion USING btree (cliente_corporativo_id);


--
-- Name: idx_sede_nic; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sede_nic ON public.sede_suscripcion USING btree (nic) WHERE (nic IS NOT NULL);


--
-- Name: idx_vigencia_concepto; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_vigencia_concepto ON public.vigencia_concepto USING btree (concepto_tributario_id, vigente_desde);


--
-- Name: uq_cartera_snapshot_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_cartera_snapshot_hash ON public.cartera_snapshot USING btree (hash_sha256) WHERE (hash_sha256 IS NOT NULL);


--
-- Name: uq_factura_cadena_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_factura_cadena_version ON public.factura USING btree (factura_origen_id, numero_version);


--
-- Name: uq_factura_cliente_periodo_activa; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_factura_cliente_periodo_activa ON public.factura USING btree (cliente_corporativo_id, periodo_facturacion_id) WHERE ((estado <> ALL (ARRAY['ANULADA'::public.estado_factura, 'REEMPLAZADA'::public.estado_factura])) AND ((origen_documento)::text = 'INTERNO'::text));


--
-- Name: uq_factura_documento_externo; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_factura_documento_externo ON public.factura USING btree (clave_documento_externo) WHERE (clave_documento_externo IS NOT NULL);


--
-- Name: uq_pago_idempotencia; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_pago_idempotencia ON public.pago USING btree (clave_idempotencia) WHERE (clave_idempotencia IS NOT NULL);


--
-- Name: uq_pago_transaccion_nueva; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_pago_transaccion_nueva ON public.pago USING btree (medio_pago, cuenta_banco, numero_transaccion) WHERE (clave_idempotencia IS NOT NULL);


--
-- Name: archivo_importado_linea archivo_importado_linea_archivo_importado_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.archivo_importado_linea
    ADD CONSTRAINT archivo_importado_linea_archivo_importado_id_fkey FOREIGN KEY (archivo_importado_id) REFERENCES public.archivo_importado(id) ON DELETE CASCADE;


--
-- Name: archivo_importado_linea archivo_importado_linea_novedad_orden_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.archivo_importado_linea
    ADD CONSTRAINT archivo_importado_linea_novedad_orden_id_fkey FOREIGN KEY (novedad_orden_id) REFERENCES public.novedad_orden(id);


--
-- Name: archivo_importado_linea archivo_importado_linea_sede_suscripcion_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.archivo_importado_linea
    ADD CONSTRAINT archivo_importado_linea_sede_suscripcion_id_fkey FOREIGN KEY (sede_suscripcion_id) REFERENCES public.sede_suscripcion(id);


--
-- Name: campo_extra_factura campo_extra_factura_definicion_campo_extra_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campo_extra_factura
    ADD CONSTRAINT campo_extra_factura_definicion_campo_extra_id_fkey FOREIGN KEY (definicion_campo_extra_id) REFERENCES public.definicion_campo_extra(id);


--
-- Name: campo_extra_factura campo_extra_factura_factura_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campo_extra_factura
    ADD CONSTRAINT campo_extra_factura_factura_id_fkey FOREIGN KEY (factura_id) REFERENCES public.factura(id) ON DELETE CASCADE;


--
-- Name: cartera_snapshot_linea cartera_snapshot_linea_cartera_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cartera_snapshot_linea
    ADD CONSTRAINT cartera_snapshot_linea_cartera_snapshot_id_fkey FOREIGN KEY (cartera_snapshot_id) REFERENCES public.cartera_snapshot(id) ON DELETE CASCADE;


--
-- Name: cartera_snapshot_linea cartera_snapshot_linea_factura_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cartera_snapshot_linea
    ADD CONSTRAINT cartera_snapshot_linea_factura_id_fkey FOREIGN KEY (factura_id) REFERENCES public.factura(id);


--
-- Name: cliente_corporativo cliente_corporativo_marca_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cliente_corporativo
    ADD CONSTRAINT cliente_corporativo_marca_id_fkey FOREIGN KEY (marca_id) REFERENCES public.marca(id);


--
-- Name: cliente_corporativo cliente_corporativo_zona_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cliente_corporativo
    ADD CONSTRAINT cliente_corporativo_zona_id_fkey FOREIGN KEY (zona_id) REFERENCES public.zona(id);


--
-- Name: cuota_instalacion cuota_instalacion_sede_suscripcion_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cuota_instalacion
    ADD CONSTRAINT cuota_instalacion_sede_suscripcion_id_fkey FOREIGN KEY (sede_suscripcion_id) REFERENCES public.sede_suscripcion(id);


--
-- Name: documento_ajuste documento_ajuste_factura_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documento_ajuste
    ADD CONSTRAINT documento_ajuste_factura_id_fkey FOREIGN KEY (factura_id) REFERENCES public.factura(id);


--
-- Name: factura factura_cliente_corporativo_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.factura
    ADD CONSTRAINT factura_cliente_corporativo_id_fkey FOREIGN KEY (cliente_corporativo_id) REFERENCES public.cliente_corporativo(id);


--
-- Name: factura factura_lote_exportacion_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.factura
    ADD CONSTRAINT factura_lote_exportacion_id_fkey FOREIGN KEY (lote_exportacion_id) REFERENCES public.lote_exportacion(id);


--
-- Name: factura_pdf_historico factura_pdf_historico_factura_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.factura_pdf_historico
    ADD CONSTRAINT factura_pdf_historico_factura_id_fkey FOREIGN KEY (factura_id) REFERENCES public.factura(id) ON DELETE CASCADE;


--
-- Name: factura factura_periodo_facturacion_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.factura
    ADD CONSTRAINT factura_periodo_facturacion_id_fkey FOREIGN KEY (periodo_facturacion_id) REFERENCES public.periodo_facturacion(id);


--
-- Name: factura fk_factura_anterior; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.factura
    ADD CONSTRAINT fk_factura_anterior FOREIGN KEY (factura_anterior_id) REFERENCES public.factura(id);


--
-- Name: factura fk_factura_origen; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.factura
    ADD CONSTRAINT fk_factura_origen FOREIGN KEY (factura_origen_id) REFERENCES public.factura(id);


--
-- Name: factura fk_factura_reemplazada_por; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.factura
    ADD CONSTRAINT fk_factura_reemplazada_por FOREIGN KEY (reemplazada_por_id) REFERENCES public.factura(id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: item_documento_ajuste item_documento_ajuste_documento_ajuste_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item_documento_ajuste
    ADD CONSTRAINT item_documento_ajuste_documento_ajuste_id_fkey FOREIGN KEY (documento_ajuste_id) REFERENCES public.documento_ajuste(id) ON DELETE CASCADE;


--
-- Name: item_documento_ajuste item_documento_ajuste_item_facturable_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item_documento_ajuste
    ADD CONSTRAINT item_documento_ajuste_item_facturable_id_fkey FOREIGN KEY (item_facturable_id) REFERENCES public.item_facturable(id);


--
-- Name: item_facturable item_facturable_factura_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item_facturable
    ADD CONSTRAINT item_facturable_factura_id_fkey FOREIGN KEY (factura_id) REFERENCES public.factura(id) ON DELETE CASCADE;


--
-- Name: item_facturable item_facturable_sede_suscripcion_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item_facturable
    ADD CONSTRAINT item_facturable_sede_suscripcion_id_fkey FOREIGN KEY (sede_suscripcion_id) REFERENCES public.sede_suscripcion(id);


--
-- Name: lote_exportacion lote_exportacion_periodo_facturacion_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lote_exportacion
    ADD CONSTRAINT lote_exportacion_periodo_facturacion_id_fkey FOREIGN KEY (periodo_facturacion_id) REFERENCES public.periodo_facturacion(id);


--
-- Name: mapeo_cuenta_contable mapeo_cuenta_contable_marca_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mapeo_cuenta_contable
    ADD CONSTRAINT mapeo_cuenta_contable_marca_id_fkey FOREIGN KEY (marca_id) REFERENCES public.marca(id);


--
-- Name: novedad_orden novedad_orden_factura_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.novedad_orden
    ADD CONSTRAINT novedad_orden_factura_id_fkey FOREIGN KEY (factura_id) REFERENCES public.factura(id);


--
-- Name: novedad_orden novedad_orden_periodo_facturacion_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.novedad_orden
    ADD CONSTRAINT novedad_orden_periodo_facturacion_id_fkey FOREIGN KEY (periodo_facturacion_id) REFERENCES public.periodo_facturacion(id);


--
-- Name: novedad_orden novedad_orden_sede_suscripcion_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.novedad_orden
    ADD CONSTRAINT novedad_orden_sede_suscripcion_id_fkey FOREIGN KEY (sede_suscripcion_id) REFERENCES public.sede_suscripcion(id);


--
-- Name: novedad_orden novedad_orden_tipo_novedad_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.novedad_orden
    ADD CONSTRAINT novedad_orden_tipo_novedad_id_fkey FOREIGN KEY (tipo_novedad_id) REFERENCES public.tipo_novedad(id);


--
-- Name: pago pago_cliente_corporativo_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pago
    ADD CONSTRAINT pago_cliente_corporativo_id_fkey FOREIGN KEY (cliente_corporativo_id) REFERENCES public.cliente_corporativo(id);


--
-- Name: pago_factura pago_factura_factura_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pago_factura
    ADD CONSTRAINT pago_factura_factura_id_fkey FOREIGN KEY (factura_id) REFERENCES public.factura(id);


--
-- Name: pago_factura pago_factura_pago_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pago_factura
    ADD CONSTRAINT pago_factura_pago_id_fkey FOREIGN KEY (pago_id) REFERENCES public.pago(id) ON DELETE CASCADE;


--
-- Name: retencion_pago retencion_pago_pago_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retencion_pago
    ADD CONSTRAINT retencion_pago_pago_id_fkey FOREIGN KEY (pago_id) REFERENCES public.pago(id) ON DELETE CASCADE;


--
-- Name: retencion_pago retencion_pago_vigencia_concepto_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retencion_pago
    ADD CONSTRAINT retencion_pago_vigencia_concepto_id_fkey FOREIGN KEY (vigencia_concepto_id) REFERENCES public.vigencia_concepto(id);


--
-- Name: sede_suscripcion sede_suscripcion_cliente_corporativo_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sede_suscripcion
    ADD CONSTRAINT sede_suscripcion_cliente_corporativo_id_fkey FOREIGN KEY (cliente_corporativo_id) REFERENCES public.cliente_corporativo(id);


--
-- Name: vigencia_concepto vigencia_concepto_concepto_tributario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vigencia_concepto
    ADD CONSTRAINT vigencia_concepto_concepto_tributario_id_fkey FOREIGN KEY (concepto_tributario_id) REFERENCES public.concepto_tributario(id);


--
-- PostgreSQL database dump complete
--



COMMIT;
