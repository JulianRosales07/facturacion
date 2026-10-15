# corporate-billing-service

Microservicio de **facturación corporativa** (B2B, multi-sede, por NIT) de Sistemas Palacios. Su objetivo es **generar la factura consolidada** de los clientes corporativos y producir el **archivo plano de movimiento contable** que se sube a **Siigo** (el motor externo que emite la factura oficial con QR/CUFE).

---

## Objetivo y alcance

**Dentro del alcance**
- Consolidar por NIT todas las sedes/suscripciones de un corporativo en una sola factura por periodo.
- Calcular cargos mensuales, prorrateo, instalación en cuotas, descuentos e IVA. Las retenciones (retefuente, reteica, reteiva, estampillas) se calculan **solo para el neto a pagar** — no van en la factura ni en el asiento.
- Administrar la configuración tributaria por corporativo: conceptos, bases/porcentajes con vigencia (fechados) y qué aplica a cada cliente.
- Manejar los dos tipos de facturación (F1 corporativos, F20 Mega Red/mayoristas) y los dos cortes (mes adelantado / mes vencido).
- Consumir los endpoints del sistema de Órdenes para traer suscripciones y novedades (con archivo como alternativa).
- Emitir notas crédito ligadas a su factura; anular y refacturar.
- Generar el archivo plano F1/F20 por lote y registrar el número de factura + CUFE que devuelve Siigo.

**Fuera del alcance**
- Pasarela de pagos (SP pagos).
- Saneamiento del sistema de Órdenes (lo mantiene otro equipo; entrega archivos/endpoints).
- Conciliación de pagos (tabla `pago_factura` prevista, sin lógica en v1).

**A futuro (previsto, no implementado)**: SVA (streaming, TVs, celulares) como ítems facturables y emisión DIAN (XML/CUFE), como nuevos tipos/implementaciones sin reescribir el núcleo.

---

## Arquitectura

Arquitectura **en capas (Layered / DDD-Lite)**. Regla de dependencia, de arriba hacia abajo: `web → service → domain / repository`, y `repository → domain`. Ninguna capa inferior depende de `web` ni de `service`; no se saltan capas (un controlador nunca llama directo a un repositorio).

DDD-Lite: el modelo de dominio son **entidades JPA con comportamiento** más value objects y **servicios de dominio** para el cálculo. Las reglas de negocio viven en las entidades y los servicios de dominio; los servicios de aplicación solo orquestan y fijan la transacción.

```
com.sistemaspalacios.billing
├── web/            controladores REST, DTOs, mappers, manejo de errores
├── service/        servicios de aplicación (orquestan + @Transactional)
├── domain/         model (entidades JPA con comportamiento), vo, service (calculadoras)
├── repository/     repositorios Spring Data JPA
├── infrastructure/ ordenes (cliente HTTP), siigo (exportador plano), file (importador)
└── config/         configuración de beans y propiedades
```

---

## Stack tecnológico

- **Java 21**, **Spring Boot 4.1.1**
- **Gradle** (Groovy DSL, `build.gradle`)
- **PostgreSQL 13+**, migraciones con **Flyway**
- **JUnit 5**, **Testcontainers** (Postgres real), **ArchUnit** (reglas de capas)
- Dependencias base: Spring Web, Spring Data JPA, PostgreSQL Driver, Validation, Flyway, Lombok, Actuator (ArchUnit se agrega al `build.gradle`).

---

## Requisitos previos

- JDK 21
- Docker (para PostgreSQL local y Testcontainers)
- Gradle wrapper incluido (`./gradlew`)

---

## Configuración

Propiedades principales (`application.yml`):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/billing
    username: billing
    password: ${DB_PASSWORD}
  flyway:
    enabled: true

ordenes:            # cliente hacia el sistema de Órdenes
  base-url: ${ORDENES_BASE_URL}
  credenciales: ${ORDENES_TOKEN}
  timeout-ms: 5000
  reintentos: 3
```

Credenciales y URLs se toman de variables de entorno / secretos, nunca del código.

---

## Cómo ejecutar

```bash
# 1) Postgres local
docker run --name billing-db -e POSTGRES_DB=billing -e POSTGRES_USER=billing \
  -e POSTGRES_PASSWORD=billing -p 5432:5432 -d postgres:16

# 2) Migraciones + arranque (Flyway corre al iniciar)
./gradlew bootRun

# 3) Health
curl http://localhost:8080/actuator/health
```

---

## Base de datos

Migración inicial `V1__esquema_inicial.sql` (Flyway). Tablas principales: `cliente_corporativo`, `sede_suscripcion`, `periodo_facturacion`, `novedad_orden`, `cuota_instalacion`, `factura`, `item_facturable`, `documento_ajuste`, `lote_exportacion`, `mapeo_cuenta_contable`, la configuración tributaria (`concepto_tributario`, `vigencia_concepto`, `perfil_tributario_cliente`), `archivo_importado(_linea)` y catálogos (`marca`, `zona`, `tipo_novedad`). `pago_factura` queda prevista, fuera de alcance.

Invariantes clave garantizadas en BD/dominio: una sola factura activa por (cliente, periodo); snapshot fiscal inmutable en la cabecera; totales que cuadran; asiento contable balanceado (débitos = créditos), **sin líneas de retención**. El **valor oficial de la factura** (`valor_total = subtotal + IVA`) se distingue del **neto a pagar** (`valor_total − retenciones`), que se calcula aparte para el paso de pago y no altera el valor oficial ni el asiento.

---

## API REST

Base: `/api/v1`

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/ordenes/sincronizar` | Sincroniza suscripciones/novedades desde Órdenes (`periodoCodigo`) |
| POST | `/ordenes/importar-archivo` | Carga manual por archivo (alternativa) |
| POST | `/facturas/generar` | Genera consolidada (`periodoCodigo`, `tipoFacturacion`, `tipoCorte`, `nit?`) |
| GET | `/facturas/{id}` | Consulta una factura |
| GET | `/facturas?periodo=&nit=&estado=` | Lista/consulta facturas |
| POST | `/facturas/{id}/anular` | Anula (`motivo`) |
| POST | `/facturas/{id}/notas-credito` | Emite nota crédito |
| GET/POST/PUT | `/config/conceptos`, `/config/vigencias` | Configura conceptos tributarios y sus bases/porcentajes con vigencia |
| GET/POST/PUT | `/clientes/{id}/perfil-tributario` | Asigna qué conceptos aplican al corporativo |
| POST | `/lotes/generar` | Genera el archivo plano del corte |
| POST | `/lotes/{id}/confirmar` | Registra números/CUFE devueltos por Siigo |
| GET | `/lotes/{id}/archivo` | Descarga el archivo plano |
| GET | `/facturas/{id}/pdf` | Referencia/descarga del PDF oficial capturado de Siigo (lo consume la pasarela) |

---

## Integraciones externas

- **Sistema de Órdenes** (entrada): endpoints REST consumidos vía la interfaz `ClienteSistemaOrdenes`. El micro mantiene su propia copia denormalizada; no comparte BD ni FKs.
- **Siigo** (salida): archivo plano de movimiento contable (asiento de doble partida). El consecutivo devuelto trae número, estado y CUFE. Además, Siigo deja las **facturas oficiales en PDF** en una **carpeta compartida (ruta de red)**; el micro las **captura y asocia** a su factura (`ruta_pdf`) emparejando por el **nombre del archivo** (`F<código><consecutivo>0000.PDF`, p. ej. `F001…519930000.PDF` → FESP 51993 F1; `F020…0002870000.PDF` → FEMG 287 F20) y, como respaldo, por **CUFE**. Las expone por `GET /facturas/{id}/pdf` para que la pasarela las entregue al corporativo (el micro no genera ni muestra el PDF). Prefijos: **F1 = FESP**, **F20 = FEMG**.

---

## Pruebas

```bash
./gradlew test
```

- **Unitarias de dominio**: todas las reglas de cálculo (prorrateo, impuestos, cuotas, descuentos) y el balance del asiento.
- **Integración (Testcontainers)**: persistencia y el índice único de factura activa por periodo.
- **ArchUnit**: valida el orden de capas y que `web` no acceda directo a `repository`.

---

## Convenciones

- **Idioma**: inglés para el nombre del servicio y los segmentos de capa; español para todo el negocio y la BD. Términos fiscales se conservan (`nit`, `iva`, `retefuente`, `reteica`, `reteiva`, `F1`, `F20`, `prorrateo`, `cufe`).
- **Dinero**: `BigDecimal`/`NUMERIC` con redondeo definido en el VO `Dinero`; nunca `double`.
- **Errores**: excepciones de dominio traducidas a HTTP en un `@RestControllerAdvice`.
- **Commits**: pequeños y descriptivos por fase/sprint.

---

## Roadmap

El desarrollo se hace por sprints, uno a la vez, con compuerta de pruebas entre cada uno. Ver `PLAN_DE_DESARROLLO_SPRINTS.md`.

## Pendientes por confirmar

1. Mapeo de cuentas contables (ingreso único o por servicio/marca/zona; propósito de las líneas en `1405011501`).
2. Ejemplo del archivo F20.
3. Quién asigna el consecutivo (Siigo o el micro).
4. Contrato de los endpoints de Órdenes (URLs, verbos, auth, JSON).
5. Bases/porcentajes vigentes por concepto (retefuente internet/software, reteica territorial, estampillas) para sembrar `vigencia_concepto`.
6. Ruta exacta / acceso a la carpeta compartida de PDF de Siigo (la convención de nombres ya está resuelta: `F<código><consecutivo>0000.PDF`).

**Resuelto en las entrevistas:** las retenciones solo afectan el neto a pagar (no la factura ni el asiento); la configuración tributaria la administra este micro; estampillas y reteica territorial entran en v1.
