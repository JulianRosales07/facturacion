# Prompt de construcción — `corporate-billing-service`

> **Cómo usar este documento:** es la especificación completa para que el agente construya el microservicio por fases. Pégala en el agente de Claude dentro de IntelliJ. Antes de la Fase 6, reemplaza el bloque marcado `<<< FORMATO Siigo F1/F20 >>>` con el layout real del archivo plano. Construye **una fase a la vez**: no pases a la siguiente hasta que los tests y ArchUnit estén en verde.

---

## 0. Convención de nombres (obligatoria y transversal)

- **Inglés:** el nombre del servicio/artefacto/repositorio (`corporate-billing-service`), el paquete base (`com.sistemaspalacios.billing`) y los segmentos estructurales de las capas: `web`, `controller`, `dto`, `mapper`, `service`, `domain`, `model`, `vo`, `repository`, `infrastructure`, `config`.
- **Español:** absolutamente todo lo que toca el negocio — clases, interfaces, métodos, variables, campos, y toda la base de datos (que ya está en español). Ejemplos: `Factura`, `SedeSuscripcion`, `ServicioFacturacion`, `RepositorioFactura`, `calcularProrrateo(...)`.
- Los términos fiscales/regulatorios se conservan tal cual (`nit`, `iva`, `retefuente`, `reteica`, `reteiva`, `F1`, `F20`, `prorrateo`, `cufe`).
- Narrativa, comentarios y documentación: español.

---

## 1. Rol y objetivo

Eres un ingeniero senior de backend. Construye un microservicio cuyo **único objetivo es generar la factura consolidada** de clientes corporativos (B2B, multi-sede, identificados por NIT) y producir el **archivo plano** que se sube a Siigo (el motor externo que emite la factura oficial con QR/CUFE).

**Dentro del alcance:**
- Consolidar por NIT todas las sedes/suscripciones de un corporativo en **una sola factura** por periodo.
- Calcular valores: cargos mensuales, prorrateo, instalación en cuotas, descuentos, IVA y retenciones (retefuente, reteica, reteiva).
- Manejar los dos tipos de facturación (**F1** corporativos, **F20** Mega Red/mayoristas) y los dos cortes (**mes adelantado**, **mes vencido**).
- Ingerir los archivos/novedades que entrega el sistema de Órdenes (integración por archivo, no en vivo).
- Emitir notas crédito ligadas a su factura; anular y refacturar.
- Generar el archivo plano F1/F20 por lote y registrar los números de factura que devuelve Siigo.

**Fuera del alcance (NO lo construyas):**
- La pasarela de pagos (SP pagos).
- El saneamiento del sistema viejo de Órdenes (lo hace otra persona; a ti te entrega archivos y novedades).
- La conciliación de pagos (se deja la tabla `pago_factura` prevista pero sin lógica).

**Diseña con visión a futuro, sin implementarlo aún:** SVA (streaming, TVs, celulares) como ítems facturables, y emisión DIAN (XML/CUFE). Ambos deben poder entrar como nuevos tipos/implementaciones sin reescribir el núcleo de facturación.

---

## 2. Stack y restricciones

- **Java 21**, **Spring Boot 4.1.1**.
- **Gradle** con **Groovy DSL** (`build.gradle`).
- **PostgreSQL 13+**. Migraciones con **Flyway** (SQL versionado; el esquema de la sección 6 es `V1__esquema_inicial.sql`).
- Dependencias de Initializr: `Spring Web`, `Spring Data JPA`, `PostgreSQL Driver`, `Validation`, `Flyway Migration`, `Lombok`, `Spring Boot Actuator`.
- Tests: **JUnit 5**, **Testcontainers** (Postgres real para la capa de persistencia), **ArchUnit** (para reglas de capas).
- El paquete base es `com.sistemaspalacios.billing`.

---

## 3. Arquitectura en capas (Layered / DDD-Lite)

Capas y regla de dependencia, de arriba hacia abajo (ArchUnit lo valida, sección 11): **web → service → domain / repository**, y `repository` → `domain`. Ninguna capa inferior depende de `web` ni de `service`; no se saltan capas (un controlador nunca llama directo a un repositorio: pasa por un servicio de aplicación).

**DDD-Lite:** el modelo de dominio son **entidades JPA con comportamiento** (anotadas `@Entity`/`@Table`/`@Column` sobre las tablas en español), más value objects y **servicios de dominio** para el cálculo. No hay puertos/adaptadores ni mappers dominio↔entidad; las reglas de negocio viven en las entidades y en los servicios de dominio, no en los servicios de aplicación.

Árbol de paquetes:

```
com.sistemaspalacios.billing
├── web/                 (capa de presentación)
│   ├── controller/       FacturaController, OrdenController, LoteController
│   ├── dto/              request/response
│   ├── mapper/           DTO ↔ dominio (MapStruct o a mano)
│   └── error/            @RestControllerAdvice + respuestas de error
├── service/             (servicios de aplicación: orquestan + @Transactional)
│                        ServicioFacturacion, ServicioOrdenes,
│                        ServicioLote, ServicioNotaCredito,
│                        ServicioConfiguracionTributaria
├── domain/              (DDD-Lite: entidades JPA con comportamiento)
│   ├── model/            Factura, ItemFacturable, ClienteCorporativo, SedeSuscripcion,
│   │                     NovedadOrden, PeriodoFacturacion, DocumentoAjuste,
│   │                     CuotaInstalacion, LoteExportacion  (+ enums de estado)
│   ├── vo/               Dinero, PerfilTributario, ResultadoProrrateo, RangoFechas
│   └── service/          CalculadoraFacturacion, CalculadoraImpuestos,
│                         CalculadoraProrrateo, GeneradorAsientoContable
├── repository/          (Spring Data JPA)
│                        RepositorioFactura, RepositorioCliente, RepositorioSede,
│                        RepositorioNovedad, RepositorioPeriodo, RepositorioLote
├── infrastructure/      (integraciones externas)
│   ├── ordenes/          ClienteSistemaOrdenes (interfaz) + impl HTTP → endpoints de Órdenes
│   ├── siigo/            ExportadorArchivoPlanoSiigo (implementa EmisorFacturaOficial),
│   │                     ImportadorConsecutivoSiigo, ImportadorPdfSiigo
│   └── file/             (alternativa) parser de archivo de Órdenes/novedades
└── config/              configuración de beans, propiedades
```

Los servicios de dominio (`domain/service`) son lógica pura, testeable sin arrancar Spring. Los servicios de aplicación (`service`) orquestan: cargan entidades por repositorio, invocan los servicios de dominio, persisten, y fijan la transacción (`@Transactional`).

---

## 4. Modelo de dominio

**Agregado raíz `Factura`** — cabecera consolidada + sus `ItemFacturable`. Invariantes que el dominio debe garantizar:
- Una factura agrupa N sedes del **mismo NIT** en un **mismo periodo**.
- Solo puede existir **una factura activa** (estado ≠ `ANULADA`) por cliente y periodo. Refacturar exige que la anterior esté `ANULADA`.
- Los datos fiscales del cliente se copian como **snapshot inmutable** en la cabecera al momento de facturar (nit, razón social, dirección, email, perfil tributario). Cambios posteriores del cliente no alteran facturas ya emitidas.
- Los totales de la cabecera **deben cuadrar** con la suma de los ítems (bruto, descuento, base gravable, IVA, retenciones, neto).
- Una factura sin ítems no puede emitirse.

**Otros agregados/entidades:** `ClienteCorporativo` (por NIT, con su `PerfilTributario`), `SedeSuscripcion`, `NovedadOrden`, `PeriodoFacturacion`, `CuotaInstalacion`, `DocumentoAjuste` (nota crédito/débito), `LoteExportacion`.

**Value objects:** `Dinero` (envuelve `BigDecimal`, moneda COP, redondeo definido), `PerfilTributario` (conceptos que aplican al cliente, resueltos contra las vigencias por fecha), `ResultadoRetenciones` (detalle por concepto + `neto_a_pagar`), `ResultadoProrrateo`, `RangoFechas`.

En DDD-Lite estas clases son las mismas `@Entity` de JPA: las invariantes se protegen dentro de sus métodos (sin setters que dejen el estado inconsistente) y el cálculo pesado se delega a los servicios de dominio.

---

## 5. Reglas de negocio de facturación

Estas reglas viven en los servicios de dominio (`domain/service`) como lógica pura y son el corazón del microservicio; el resto de capas se apoya en ellas. Deben tener tests unitarios exhaustivos **antes** de tocar la BD.

1. **Fecha de cobro.** La fecha real de cobro de cada sede es su `fecha_certificacion` (certificación de ingeniería), **salvo** que exista una observación comercial que indique explícitamente otra fecha. No se usa la fecha de creación de la orden ni de la suscripción.
2. **Cargo mensual.** Cada sede activa aporta su `valor_mensual` al periodo.
3. **Prorrateo: cargo fijo + otros conceptos.** Cuando una sede tiene novedades con `fecha_efectiva` dentro del periodo, su cobro se arma así (es lo que el cliente ve en "Cargos fijos" y "Otros conceptos"):
   - **Cargo fijo:** el plan vigente el día 1, por los días que el servicio estuvo activo en el periodo (desde la certificación si es posterior al día 1, hasta el día anterior a una BAJA/SUSPENSION). No se modifica por los cambios de plan.
   - **Una línea por novedad en Otros conceptos:**
     - CAMBIO_PLAN / CAMBIO_SERVICIO: **incremento** `(valor_nuevo − valor_anterior) × días_comerciales desde fecha_efectiva hasta el fin del servicio / 30`. Descripción `… INCREMENTO POR NOVEDAD GR 16-31 OCT 2026`.
     - ALTA / REACTIVACION de un servicio que no estaba activo: su plan por los días desde `fecha_efectiva`.
     - Una **bajada de plan** daría un incremento negativo, que la factura electrónica no admite: se aplica como **descuento por valor** sobre el cargo fijo de la sede (y si lo supera, sobre sus otras líneas). En el detalle se muestra como "Descuento por bajada de plan".
     - Un cambio efectivo el día 1 (o retroactivo) cambia directamente el cargo fijo: no se cobró ningún día al plan anterior.
     - TRASLADO_DIRECCION no tiene efecto económico.
   - La suma por sede es la misma que cobrar cada tramo a su tarifa (salvo diferencias de un peso por redondeo de cada línea).
   - **Tarifa de apertura:** la `valor_anterior` que guardó la primera novedad de cambio de plan/servicio de la sede en el periodo (la importación la toma de la sede antes de actualizarla, así que no importa si la novedad llega antes o después de generar la factura); si no hay, al recalcular la factura del mismo periodo, la del cargo fijo de esa factura; si no, la tarifa con que **terminó** el periodo anterior (última novedad que fija tarifa en ese periodo, o el cargo fijo de su factura); solo en último caso `sede.valor_mensual`. La tarifa nueva de cada novedad es `novedad_orden.valor`, o `sede.valor_mensual` si no trae valor.
   - **Mes comercial de 30 días:** `valor = valor_mensual × días_comerciales / 30`. Todo mes cuenta 30 días: el día 31 no cuenta y el último día de febrero completa hasta 30. Un mes completo se cobra por su `valor_mensual` exacto.
     - Mes de 31 días con cambio el 16: el incremento cubre 15 días (no 16).
     - Febrero con cambio el 15: el incremento cubre 16 días (no 14).
     - Cambio efectivo el día 31: no genera incremento; el plan nuevo arranca el mes siguiente. Un ALTA el día 31 no cobra nada ese mes.
   - Varias novedades de la misma sede se aplican en orden de `fecha_efectiva` (luego fecha de creación, luego id); cada una aporta su incremento respecto de la tarifa que dejó la anterior.
   - Cada concepto es un `ItemFacturable` con la misma `sede_suscripcion_id`, su `valor_mensual_base` (tarifa vigente después de él), su rango `fecha_desde`/`fecha_hasta` y, si viene de una novedad, su `novedad_orden_id`.
   - El cálculo es **por sede y antes de IVA**. Descuento, IVA y retenciones se aplican sobre cada ítem (reglas 5, 6 y 7).
   - **Redondeo:** cada línea se redondea al peso (HALF_UP) sobre `valor × días / 30`, nunca multiplicando por un factor ya redondeado.
   - La sincronización de órdenes deja la sede con `valor_mensual` = valor nuevo, de modo que el periodo siguiente se cobra completo con el plan nuevo.
   - **Novedad sobre un periodo ya emitido o pagado** (supuesto: el documento cobró el periodo a la tarifa anterior; `<confirmar con contabilidad>` frente a nota débito/crédito):
     - El documento emitido (interno con CUFE, en lote de exportación, con pagos o notas, o el documento oficial de Siigo del periodo) **no se modifica**.
     - Se calcula el **impacto** de la novedad en su periodo: cambio de plan `(valor − valor_anterior) × días / 30`, alta `valor × días / 30`, baja o suspensión `−valor_anterior × días / 30`. La novedad queda `PENDIENTE_AJUSTE` con ese `valor_impacto`.
     - El ajuste entra en la **siguiente factura en preparación** del cliente: positivo como línea `… AJUSTE <TIPO> PERIODO <aaaa-mm> GR …` (Otros conceptos · Ajustes y descuentos); negativo como descuento por valor sobre sus líneas (nunca una línea negativa). Si esa factura ya existe, se recalcula de inmediato y la pasarela ve el valor nuevo. Lo que no alcance a compensarse queda como saldo a favor (registrado en el log).
     - Si el impacto no se puede calcular (tarifa anterior desconocida o "no cobrar prorrateo"), queda en revisión y no se cobra solo.
     - Una novedad retroactiva (fecha efectiva anterior al periodo importado) se registra en el periodo de su fecha.
     - Las notas crédito y débito siguen disponibles como mecanismo manual.
   - **Excepción:** si la observación de la novedad dice "no cobrar prorrateo", se cobra el mes completo con el plan indicado (como cargo fijo), o se omite, según lo que diga la observación.

   Ejemplo (octubre, plan de 500.000; sube a 800.000 el 16 y a 1.100.000 el 21; valores sin IVA):

   | Bloque | Concepto | Cálculo | Valor |
   |---|---|---|---|
   | Cargos fijos | Servicio · plan 500.000, 01–31 oct | mes completo | 500.000 |
   | Otros conceptos | Cambio de plan a 800.000 desde el 16 | (800.000 − 500.000) × 15 / 30 | 150.000 |
   | Otros conceptos | Cambio de plan a 1.100.000 desde el 21 | (1.100.000 − 800.000) × 10 / 30 | 100.000 |
   | **Total de la sede antes de IVA** | | | **750.000** |

   Con IVA del 19 %, el total es 892.500. Cobrando cada tramo a su tarifa da lo mismo: 250.000 + 133.333 + 366.667.

   **Detalle de cobros (representación gráfica y pantalla):** la factura se presenta en tres bloques, cada uno con su IVA y su total: *Deuda anterior* (cargos y pagos de las facturas oficiales de Siigo del cliente emitidas antes del periodo, con el saldo del corte de cartera cuando existe), *Cargos fijos* y *Otros conceptos* (novedades, cuotas de instalación y descuentos por bajada de plan). Cargos fijos + Otros conceptos = total de la factura; el total a pagar suma además la deuda anterior.

4. **Instalación en cuotas.** Si la sede tiene una `CuotaInstalacion` con saldo pendiente, se agrega un ítem por la cuota del periodo y se incrementa `cuotas_facturadas`.
5. **Descuentos.** Aplicables por ítem (`porcentaje_descuento`) o a nivel de cabecera, según lo indicado en la novedad/observación.
6. **IVA.** Se calcula por ítem gravado usando su `porcentaje_iva`. Los ítems no gravados no suman IVA.
7. **Retenciones (solo para el neto a pagar; NO van en la factura ni en el asiento).** El valor oficial de la factura es `subtotal + IVA`; las retenciones no lo alteran. Aparte, se calculan retefuente, reteica, reteiva y estampillas según el perfil tributario del cliente y la **vigencia** (base + porcentaje) aplicable a la fecha del corte, para obtener `neto_a_pagar = valor_total − retenciones`. Ese neto se entrega al paso de pago; la factura conserva su valor. La base/porcentaje salen de `vigencia_concepto` según la clasificación (internet/software) y, para reteica (territorial), el municipio.
8. **Consolidación.** Una factura por (cliente, periodo, tipo_facturacion). Los ítems referencian su `sede_suscripcion_id` (excepto SVA a futuro, que puede no tener sede).
   - **Novedades sobre la factura en preparación.** Una factura está *en preparación* si es interna, está `CONSOLIDADA`, no tiene CUFE, no está en un lote de exportación a Siigo y no tiene pagos ni notas crédito. Cuando se importan o sincronizan novedades de un cliente, su factura en preparación del periodo se recalcula **en su lugar** (mismo id, número y versión) con la regla 3 (cargo fijo + una línea por novedad); no se crea otra factura ni otra versión.
   - Si el cliente aún no tiene factura del periodo, las novedades quedan registradas y entran al generarla. Si la factura ya no está en preparación, no se modifica y las novedades quedan pendientes de facturar.
   - Una novedad se identifica por (sede, periodo, tipo, fecha_efectiva): reenviarla la actualiza sin duplicarla; dos cambios de plan en fechas distintas son dos novedades.
   - Cada novedad registra su estado (`PENDIENTE`, `APLICADA`, `PENDIENTE_AJUSTE`, `AJUSTE_APLICADO`), `valor_anterior`, `valor_impacto`, detalle y fecha de procesamiento, y la factura donde se cobró (`factura_id`). Una novedad se cobra en un solo documento.
   - El recálculo bloquea la fila de la factura (`SELECT … FOR UPDATE`): dos procesos sobre la misma factura se ejecutan uno tras otro.
   - `POST /sincronizacion/consumir-novedades` reprocesa de forma idempotente las novedades del cliente y periodo; `GET /sincronizacion/novedades/clientes/{nit}` las muestra con su estado e impacto.
   - La importación del archivo y la actualización de la factura van en una sola transacción: si el recálculo falla, no queda registrada ninguna novedad del archivo.
9. **Asiento de doble partida.** A partir de la factura, se genera el movimiento contable balanceado (ver sección 9): un crédito a ingresos por cada sede, un crédito de IVA y un débito a cartera por el total. **El asiento NO incluye líneas de retención** (las retenciones viven solo en el cálculo del neto a pagar, regla 7). La suma de débitos debe igualar la de créditos; el motor debe rechazar un asiento descuadrado. Las cuentas salen de `mapeo_cuenta_contable`.

10. **Configuración tributaria (la administra este micro).** Los conceptos (retefuente, reteica, reteiva, estampilla), sus bases/porcentajes con **vigencia** (fechados; cambian por decisión del gobierno, varias veces al año) y la **asignación por corporativo** (qué aplica y con qué porcentaje) se administran vía CRUD en este microservicio. La base es global; el porcentaje puede variar por cliente, por clasificación (internet/software) y —para reteica— por municipio.

Cada regla anterior debe cubrirse con casos de borde en los tests: novedad a mitad de periodo, cliente con y sin retenciones, cambio de base de retención a mitad de año (vigencias), reteica territorial, servicio clasificado como internet vs software, sede con cuota que se agota en el periodo, observación que anula prorrateo, ítem no gravado, y un asiento (sin retenciones) que debe cuadrar débitos = créditos.

Casos de borde del prorrateo (regla 3):

- Cambio de plan el día 16 en un mes de 30 días: cargo fijo del mes completo + un incremento de 15 días.
- Cambio de plan el día 16 en un mes de 31 días: el incremento cubre 15 días comerciales; mismo total que en septiembre.
- Cambio de plan el día 15 de febrero (28 y 29 días): el incremento cubre 16 días comerciales.
- Cambio de plan o ALTA el día 31: cero días, no genera línea.
- Dos novedades de la misma sede en el mismo periodo: cargo fijo + una línea por novedad, cada una respecto de la tarifa anterior.
- Novedad con `fecha_efectiva` = primer día del periodo: una sola línea, cargo fijo del mes al plan nuevo.
- Bajada de plan (valor nuevo menor): descuento por valor sobre el cargo fijo, sin líneas negativas; la factura cuadra.
- Tarifa de apertura de un mes = la del cierre del anterior (no la de su día 1).
- Detalle de cobros: Cargos fijos + Otros conceptos = total de la factura; la deuda anterior sale de los documentos de Siigo previos al periodo.
- Redondeo: `15.000.000 × 16 / 30` da exactamente 8.000.000 (sin pérdida por redondear el factor).
- Mes adelantado con factura ya confirmada: subida → ítem de ajuste en el periodo siguiente; bajada → nota crédito.

---

## 6. Esquema de datos ajustado (`V1__esquema_inicial.sql`)

Parte del esquema base que ya existe, con estos **ajustes obligatorios** respecto a la versión original:
- `sede_suscripcion`: se agregan `valor_mensual`, `fecha_certificacion` y `nic`.
- `novedad_orden`: se agregan `valor` y `fecha_efectiva` (para poder prorratear).
- Nueva tabla `cuota_instalacion` (instalación en cuotas a través de periodos).
- Nueva tabla `lote_exportacion` + columna `lote_exportacion_id` en `factura` (salida hacia Siigo, espejo de la ingesta).
- `factura`: se agregan `cufe` y `estado_envio` (lo que devuelve el consecutivo de Siigo).
- Nueva tabla `mapeo_cuenta_contable` (cuentas de ingreso/IVA/cartera y cuenta de orden para generar el asiento de doble partida — ver sección 9). **El asiento no lleva retenciones.**
- **Configuración tributaria fechada**: nuevas tablas `concepto_tributario`, `vigencia_concepto` (base + porcentaje con vigencia) y `perfil_tributario_cliente` (qué aplica a cada corporativo). En `cliente_corporativo` se quitan `porcentaje_retefuente/reteica/reteiva` (los reemplaza este modelo) y se agrega `municipio`.
- `factura`: se separa el **valor oficial** (`valor_subtotal`, `valor_iva`, `valor_total`) del **cálculo de pago** (`valor_retenciones`, `neto_a_pagar`), que no altera el valor oficial ni el asiento.
- `cliente_corporativo`: se agregan los códigos que Siigo pide por comprobante (`codigo_vendedor`, `centro_costo`, `subcentro_costo`, `codigo_ciudad`, `codigo_zona_siigo`) — **origen a confirmar**.
- `pago_factura` se conserva pero queda **fuera de alcance** (sin lógica en v1).

```sql
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
CREATE TABLE marca ( id SMALLINT PRIMARY KEY, nombre VARCHAR(50) NOT NULL UNIQUE );
INSERT INTO marca (id, nombre) VALUES (1,'Sistemas Palacios'),(2,'Meganet'),(3,'Enlaces');

CREATE TABLE zona ( id SMALLINT PRIMARY KEY, nombre VARCHAR(50) NOT NULL UNIQUE );
INSERT INTO zona (id, nombre) VALUES (1,'Pasto'),(2,'Norte'),(3,'Sur'),(4,'Occidente'),(5,'Costa');

CREATE TABLE tipo_novedad ( id SMALLINT PRIMARY KEY, nombre VARCHAR(50) NOT NULL UNIQUE );
INSERT INTO tipo_novedad (id, nombre) VALUES
    (1,'ALTA'),(2,'BAJA'),(3,'CAMBIO_PLAN'),(4,'CAMBIO_SERVICIO'),
    (5,'SUSPENSION'),(6,'REACTIVACION'),(7,'TRASLADO_DIRECCION');

-- CONFIGURACIÓN TRIBUTARIA (administrada por este micro; solo para el neto a pagar)
-- Conceptos: retefuente, reteica (territorial), reteiva, estampilla.
CREATE TABLE concepto_tributario (
    id          SMALLINT PRIMARY KEY,
    codigo      VARCHAR(20) NOT NULL UNIQUE,   -- RETEFUENTE, RETEICA, RETEIVA, ESTAMPILLA
    nombre      VARCHAR(80) NOT NULL,
    territorial BOOLEAN NOT NULL DEFAULT FALSE  -- reteica = TRUE (depende del municipio)
);
INSERT INTO concepto_tributario (id, codigo, nombre, territorial) VALUES
    (1,'RETEFUENTE','Retención en la fuente',FALSE),
    (2,'RETEICA','Retención de ICA',TRUE),
    (3,'RETEIVA','Retención de IVA',FALSE),
    (4,'ESTAMPILLA','Estampillas',TRUE);

-- Base + porcentaje con VIGENCIA (global; cambia por decisión del gobierno, varias veces al año).
-- Puede afinarse por clasificación (internet/software) o por municipio (reteica territorial).
CREATE TABLE vigencia_concepto (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    concepto_tributario_id SMALLINT NOT NULL REFERENCES concepto_tributario(id),
    clasificacion          clasificacion_servicio,  -- NULL = aplica a cualquiera
    municipio              VARCHAR(60),              -- reteica territorial; NULL = general
    base_minima            NUMERIC(14,2) NOT NULL DEFAULT 0,  -- base a partir de la cual retiene
    porcentaje             NUMERIC(5,2)  NOT NULL,
    vigente_desde          DATE NOT NULL,
    vigente_hasta          DATE,                     -- NULL = vigente
    estado                 estado_registro NOT NULL DEFAULT 'ACTIVO'
);
CREATE INDEX idx_vigencia_concepto ON vigencia_concepto(concepto_tributario_id, vigente_desde);

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
    -- Códigos que Siigo exige por comprobante (origen a confirmar):
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

-- SEDE / SUSCRIPCIÓN  (+ valor_mensual, fecha_certificacion, nic)
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

-- PERIODO
CREATE TABLE periodo_facturacion (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo       VARCHAR(7) NOT NULL UNIQUE CHECK (codigo ~ '^\d{4}-\d{2}$'),
    tipo_corte   VARCHAR(20) NOT NULL,   -- ADELANTADO | VENCIDO
    fecha_inicio DATE NOT NULL,
    fecha_fin    DATE NOT NULL,
    estado       VARCHAR(20) NOT NULL DEFAULT 'ABIERTO',   -- ABIERTO | CERRADO
    creado_en    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- NOVEDAD  (+ valor, fecha_efectiva)
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

-- MAPEO DE CUENTAS CONTABLES  (para generar el asiento de doble partida — sección 9)
-- El match puede afinarse por tipo de item / marca / zona; NULL = comodín.
-- Valores sembrados = ejemplo F1 observado (CONFIRMAR/COMPLETAR).
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
    ('ORDEN',   'F1', '1405011501', 'C');   -- líneas en 0 (confirmar propósito)

-- FACTURA (+ lote_exportacion_id)
CREATE TABLE factura (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_corporativo_id    UUID NOT NULL REFERENCES cliente_corporativo(id),
    periodo_facturacion_id    UUID NOT NULL REFERENCES periodo_facturacion(id),
    lote_exportacion_id       UUID REFERENCES lote_exportacion(id),
    tipo_facturacion          tipo_facturacion NOT NULL,
    numero_factura            VARCHAR(30),   -- lo devuelve Siigo (consecutivo del comprobante)
    cufe                      VARCHAR(96),   -- CUFE/CUDE devuelto por Siigo
    estado_envio              VARCHAR(40),   -- ej. 'Envío exitoso'
    ruta_pdf                  VARCHAR(500),  -- referencia al PDF oficial que Siigo deja en la carpeta compartida
    nit_cliente               VARCHAR(20)  NOT NULL,
    razon_social_cliente      VARCHAR(255) NOT NULL,
    direccion_fiscal_cliente  VARCHAR(255),
    email_facturacion_cliente VARCHAR(150),
    datos_fiscales_snapshot   JSONB,
    valor_bruto               NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_descuento           NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_subtotal            NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_iva                 NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_total               NUMERIC(14,2) NOT NULL DEFAULT 0,  -- VALOR OFICIAL = subtotal + IVA (lo que va a Siigo)
    -- Cálculo de pago (NO altera el valor oficial ni el asiento):
    valor_retefuente          NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_reteica             NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_reteiva             NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_estampillas         NUMERIC(14,2) NOT NULL DEFAULT 0,
    valor_total_retenciones   NUMERIC(14,2) NOT NULL DEFAULT 0,
    neto_a_pagar              NUMERIC(14,2) NOT NULL DEFAULT 0,  -- valor_total − retenciones (para el paso de pago)
    fecha_emision             DATE NOT NULL DEFAULT CURRENT_DATE,
    fecha_vencimiento         DATE,
    forma_pago                VARCHAR(20) DEFAULT 'CREDITO',
    estado                    estado_factura NOT NULL DEFAULT 'BORRADOR',
    anulado_en                TIMESTAMPTZ,
    motivo_anulacion          VARCHAR(255),
    creado_en                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en            TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Solo una factura activa por cliente+periodo (refacturar si la anterior está ANULADA)
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

-- NOTAS CRÉDITO / DÉBITO (ligadas a su factura)
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

-- INGESTA DE ARCHIVOS DE ÓRDENES (entrada)
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

-- FUERA DE ALCANCE v1 (conciliación): se deja previsto, sin lógica.
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
```

---

## 7. Contrato de entrada (Órdenes → Facturación)

**Integración principal por API.** El sistema de Órdenes va a exponer **endpoints REST** que este microservicio consume para traer las suscripciones y novedades del periodo. La conexión se aísla tras la interfaz `ClienteSistemaOrdenes` (en `infrastructure/ordenes`), con su implementación HTTP (`RestClient`: base URL, credenciales, *timeouts* y reintentos configurables). El servicio de aplicación `ServicioOrdenes` (método `sincronizar(periodo)`) llama a esa interfaz y hace *upsert* en la copia local (`cliente_corporativo`, `sede_suscripcion`, `cuota_instalacion`, `novedad_orden`).

**El micro no comparte base de datos ni FKs con el sistema de Órdenes:** consume sus endpoints y mantiene su **propia copia denormalizada** (snapshot). Ese es el límite entre servicios.

`<PENDIENTE: URLs, verbos, autenticación y esquema JSON exactos de los endpoints — los va a entregar el equipo de Órdenes>`. Define la interfaz `ClienteSistemaOrdenes` de forma abstracta (`obtenerSuscripciones(periodo)`, `obtenerNovedades(periodo)`, etc.) para conectar el contrato concreto sin tocar la lógica de negocio.

**Alternativa por archivo.** Se mantiene el importador de archivo (`infrastructure/file`) y las tablas `archivo_importado` / `archivo_importado_linea` como vía de respaldo/carga manual. Un registro de entrada (venga de la API o del archivo) trae al menos: `origen` (SPE/MEGA_RED/ENLACES), `nit`, `razon_social`, `codigo_orden_externo`, `nic`, `nombre_sede`, `valor_mensual`, `fecha_certificacion`, `tipo_novedad` (opcional), `valor_novedad` (opcional), `fecha_efectiva` (opcional), `observacion` (texto libre). El crudo se guarda para trazabilidad; los registros con error quedan marcados y no bloquean al resto.

---

## 8. Servicios de aplicación y colaboradores

Servicios de aplicación (en `service`, cada operación expuesta por un controlador REST y con su `@Transactional`):
- `ServicioOrdenes.sincronizar(periodo)` — trae suscripciones y novedades del sistema de Órdenes (vía la interfaz `ClienteSistemaOrdenes`; o desde archivo como alternativa) y actualiza los maestros.
- `ServicioFacturacion.generarConsolidada(periodo, tipoFacturacion, tipoCorte, [nit])` — genera la(s) factura(s) consolidada(s) aplicando la sección 5.
- `ServicioFacturacion.anular(facturaId, motivo)` — marca `ANULADA`; habilita refacturar.
- `ServicioNotaCredito.emitir(facturaId, ...)` — crea `documento_ajuste` ligado a una factura.
- `ServicioConfiguracionTributaria` — CRUD de conceptos, vigencias (base + % por fecha) y del perfil tributario por corporativo. `ServicioFacturacion` lo usa para calcular las retenciones y el `neto_a_pagar` (sin tocar el valor oficial ni el asiento).
- `ServicioLote.generar(periodo, tipoFacturacion, tipoCorte)` — arma el archivo plano F1/F20 del corte y crea el `lote_exportacion`.
- `ServicioLote.confirmar(...)` — registra los `numero_factura`, `cufe` y `estado_envio` que devuelve Siigo y pasa el lote a `CONFIRMADO`.
- `ServicioLote.asociarPdfs(...)` — lee la **carpeta compartida** donde Siigo deja los PDF oficiales, empareja cada archivo con su factura por el **nombre** (`F<código><consecutivo>0000.PDF` → tipo + `numero_factura`) y, como respaldo, por el **CUFE**, y guarda la referencia en `factura.ruta_pdf`. El micro **no genera ni muestra** el PDF: solo captura y asocia la referencia; servirlo al corporativo es de la pasarela.

Colaboradores: los `Repositorio…` (Spring Data JPA); la interfaz `EmisorFacturaOficial` (implementada por `ExportadorArchivoPlanoSiigo`, con DIAN como implementación futura); y la interfaz `ClienteSistemaOrdenes` (implementada por el cliente HTTP hacia los endpoints de Órdenes). Los servicios de dominio (`CalculadoraFacturacion`, `GeneradorAsientoContable`, etc.) hacen el cálculo; los servicios de aplicación solo orquestan.

---

## 9. Salida oficial (Siigo ahora, DIAN a futuro)

La emisión oficial se encapsula tras la interfaz `EmisorFacturaOficial`. La implementación de v1 es `ExportadorArchivoPlanoSiigo`, que genera el **archivo de importación de movimiento contable de Siigo (modelo general)**. DIAN (XML/CUFE) será una implementación futura de la misma interfaz: no la implementes, pero deja la interfaz lo bastante genérica para que encaje.

### 9.1 El archivo plano es un asiento contable de doble partida

No es una lista de facturas: cada factura es un **comprobante contable balanceado** (débitos = créditos), con una línea por `SECUENCIA`. Para un comprobante F1 el asiento es:
- **N líneas CRÉDITO a ingresos** (una por sede/suscripción), cada una = valor del servicio de esa sede. Su suma es la base gravable.
- **1 línea CRÉDITO de IVA** (19% sobre la base gravable).
- **1 línea DÉBITO a cartera** por el total (base + IVA) = lo que el cliente debe.
- (Pendiente confirmar) líneas en valor 0 sobre una cuenta de orden que listan los servicios activos.

Ejemplo real validado (comprobante 50776, NIT 900245045, 31 sedes): 31 créditos a ingresos = 11.060.000, IVA 19% = 2.101.400, débito a cartera = 13.161.400. Cuadra exacto. **Esto confirma la consolidación por NIT**: un comprobante = N secuencias = un solo NIT.

Las retenciones (retefuente, reteica, reteiva, estampillas) **no entran en el asiento ni en la factura**: se calculan aparte, con las vigencias por fecha, para obtener el `neto_a_pagar` que se usa en el paso de pago (ver regla 7). Por eso el asiento de un cliente con retención luce igual que el del ejemplo (ingresos + IVA = cartera).

### 9.2 Layout de columnas (una fila por secuencia, en este orden)

`TIPO DE COMPROBANTE` ('F') · `CÓDIGO COMPROBANTE` (1 = F1 prefijo FESP, 20 = F20 prefijo FEMG) · `NÚMERO DE DOCUMENTO` (consecutivo, ver 9.3) · `CUENTA CONTABLE` (mapeo 9.4) · `DÉBITO O CRÉDITO` ('D'|'C') · `VALOR DE LA SECUENCIA` · `AÑO`/`MES`/`DÍA DEL DOCUMENTO` · `CÓDIGO DEL VENDEDOR` · `CÓDIGO DE LA CIUDAD` · `CÓDIGO DE LA ZONA` · `SECUENCIA` (1,2,3… dentro del comprobante) · `CENTRO DE COSTO`/`SUBCENTRO DE COSTO` · `NIT` · `SUCURSAL` · `DESCRIPCIÓN DE LA SECUENCIA` (patrón observado `SEDE.INT.FIB.<TIPO>.<VELOCIDAD> GR <DD-DD MMM AAAA>`, ej. `LA CRUZ.INT.FIB.DEDI.1.65 GB GR 01-31 AGT 2026`; el rango de días refleja el prorrateo, ej. `PLAZUELAS INT.FIB.DEDI 200MB GR 12-31 AGT 2026`) · `NÚMERO DE CHEQUE` · `COMPROBANTE ANULADO` ('N') · `CÓDIGO DEL MOTIVO DE DEVOLUCIÓN` · `FORMA DE PAGO` · `VALOR DEL CARGO 1/2` · `VALOR DEL DESCUENTO 1/2/3` · `FACTURA ELECTRÓNICA A DEBITAR/ACREDITAR` + su número (se usan en notas crédito/débito) · luego prefijos/números de *order reference* y de *órdenes de entrega* (1 a 4), `INGRESOS PARA TERCEROS`, y `FECHA`/`HORA DE ACTUALIZACIÓN` (`AAAAMMDD` / `HHMMSS`). En facturación normal esas últimas van vacías o en 0.

Modela esto como un `RegistroMovimientoSiigo` tipado (una clase con las columnas en orden) + un escritor que respete orden, separador y formato exactos. Las columnas de *order reference* y *órdenes de entrega* son opcionales.

### 9.3 Consecutivo y confirmación de vuelta

`<PENDIENTE: definir si el NÚMERO DE DOCUMENTO lo asigna el micro o Siigo>`. El archivo `CONSECUTIVO_FACTURAS` que devuelve Siigo trae, por comprobante: número, valor, estado del documento ('Envío exitoso') y **CUFE/CUDE**. El caso de uso `ConfirmarLoteSiigo` importa ese archivo y guarda en cada `factura` su `numero_factura`, `cufe` y `estado_envio`.

### 9.5 PDF oficial de vuelta (carpeta compartida)

Después de emitir, Siigo genera la **factura oficial en PDF** (con QR/CUFE) y la deja en una **carpeta compartida (ruta de red)** a la que este micro tiene acceso. `ImportadorPdfSiigo` recorre esa carpeta, asocia cada PDF a su factura y guarda la **referencia** en `factura.ruta_pdf` (no almacena el blob). El micro expone `GET /facturas/{id}/pdf` para que **la pasarela** lo consuma y se lo entregue al corporativo. Generar el PDF es de Siigo; mostrárselo/descargárselo al corporativo es de la pasarela; este micro solo **captura, asocia y expone la referencia**.

**Convención de nombres (para emparejar).** El nombre del archivo codifica tipo + consecutivo: `F` + `<código comprobante a 3 dígitos>` + `<consecutivo con ceros a la izquierda>` + `0000` + `.PDF`. Ejemplos reales: `F001000000519930000.PDF` → F1, factura **FESP 51993**; `F020000000002870000.PDF` → F20, factura **FEMG 287**. Emparejar **primero por el nombre** (parsear código + consecutivo → `numero_factura`) y, como **respaldo**, por el **CUFE** leído del contenido. `<PENDIENTE: solo la ruta exacta de la carpeta / acceso>`.

### 9.4 Mapeo de cuentas contables `<PENDIENTE confirmar/completar>`

Configurable en la tabla `mapeo_cuenta_contable`. Valores observados en el ejemplo F1, a confirmar:
- Ingreso: `4105011501` (¿único o varía por servicio/marca/zona?)
- IVA generado: `2408010515` · Cartera corporativa: `1305051004` · Cuenta de orden (líneas en 0): `1405011501` (¿qué representa?).
- Códigos del ejemplo: vendedor 13, ciudad 670 (Pasto), zona 4, centro de costo 10, subcentro 1.
- Códigos de producto por servicio observados en facturas reales (insumo para el mapeo por servicio): `0100040000021`, `0100040000008`, `0100040000009` (F1); `0100030000013` (F20). Dato fiscal del emisor: actividad económica 313, tarifa ICA 6 por mil.

---

## 10. API REST

Base `/api/v1`. Respuestas y errores en JSON con un formato de error consistente (código, mensaje, detalles).
- `POST /ordenes/sincronizar` — dispara la sincronización con los endpoints del sistema de Órdenes; body: `periodoCodigo`. (Alternativa: `POST /ordenes/importar-archivo` para carga manual.)
- `POST /facturas/generar` — body: `periodoCodigo`, `tipoFacturacion`, `tipoCorte`, `nit?`.
- `GET  /facturas/{id}` · `GET /facturas?periodo=&nit=&estado=`
- `POST /facturas/{id}/anular` — body: `motivo`.
- `POST /facturas/{id}/notas-credito` — body: motivo + ítems.
- `GET|POST|PUT /config/conceptos` · `/config/vigencias` — administra conceptos y sus bases/porcentajes con vigencia.
- `GET|POST|PUT /clientes/{id}/perfil-tributario` — asigna qué conceptos aplican al corporativo (+ override de %).
- `POST /lotes/generar` — body: `periodoCodigo`, `tipoFacturacion`, `tipoCorte`.
- `POST /lotes/{id}/confirmar` — body: mapa factura→numeroFactura.
- `GET  /lotes/{id}/archivo` — descarga el plano.
- `GET  /facturas/{id}/pdf` — devuelve la referencia/descarga del PDF oficial capturado de Siigo (lo consume la pasarela).

---

## 11. Calidad y criterios de aceptación

- **ArchUnit** (falla el build si se viola): respeta el orden de capas `web → service → domain/repository` y `repository → domain`; ninguna capa inferior depende de `web` ni de `service`; `web` no accede directo a `repository` (pasa por `service`); no hay ciclos entre paquetes.
- **Tests unitarios de dominio** para toda la sección 5, incluyendo los casos de borde listados.
- **Tests de integración** con Testcontainers para la capa de persistencia y para el índice único de factura activa por periodo.
- Criterios de aceptación por caso de uso, p. ej. *Generar factura*: dado un cliente con 3 sedes activas, una con alta a mitad de periodo y una con cuota de instalación pendiente, la factura resultante tiene una cabecera, ítems por sede, prorrateo correcto, IVA y retenciones aplicadas, totales que cuadran, y respeta el índice de unicidad.

---

## 12. Plan de construcción por fases

Construye **una fase completa y en verde antes de la siguiente** (un slice vertical canónico primero):
- **Fase 0 — Scaffold:** proyecto Gradle (Groovy DSL) con las dependencias, Flyway `V1`, esqueleto de capas, regla ArchUnit base, Actuator/health.
- **Fase 1 — Dominio + cálculo:** entidades y VOs (sin persistir todavía) y `domain/service` con la sección 5 completa y sus tests unitarios.
- **Fase 2 — Persistencia:** anota las entidades como `@Entity` (DDD-Lite, sin mapper aparte), repositorios Spring Data, tests con Testcontainers.
- **Fase 3 — Integración con Órdenes + configuración tributaria:** interfaz `ClienteSistemaOrdenes` + impl HTTP y `ServicioOrdenes.sincronizar` (upsert de maestros y novedades), importador de archivo como alternativa; y el CRUD de configuración tributaria (`ServicioConfiguracionTributaria`: conceptos, vigencias, perfil por corporativo). Tests del cliente con un mock del servidor de Órdenes.
- **Fase 4 — Generar factura consolidada:** `ServicioFacturacion.generarConsolidada` end-to-end + controlador (el slice canónico de referencia).
- **Fase 5 — Ajustes:** anular/refacturar y notas crédito.
- **Fase 6 — Exportación Siigo:** `ExportadorArchivoPlanoSiigo` con el layout real, lote, confirmación (`numero_factura`/`cufe`/`estado_envio`) y captura del PDF oficial desde la carpeta compartida (`ImportadorPdfSiigo` → `ruta_pdf`), con `GET /facturas/{id}/pdf`.

## 13. Convenciones varias

- Manejo de errores del dominio con excepciones propias (`FacturaYaExisteException`, etc.) traducidas a HTTP en un `@RestControllerAdvice`.
- `Dinero` centraliza redondeo; nunca usar `double` para valores monetarios.
- Logging en puntos de negocio clave (import procesado, factura generada, lote exportado).
- Commits pequeños y descriptivos por fase.
