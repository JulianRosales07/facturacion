# Prompts por sprint — `corporate-billing-service`

> **Cómo usar:** pega **un sprint a la vez** en tu agente (IntelliJ). La **fuente de verdad** es `PROMPT_corporate-billing-service.md` (stack, esquema, capas, reglas). No pases al siguiente sprint hasta que su **compuerta de cierre** esté en verde. Recordatorio de decisiones firmes: Java 21 · Spring Boot 4.1.1 · Gradle (Groovy) · PostgreSQL + Flyway · arquitectura en **capas (Layered/DDD-Lite)** · nombre del servicio en inglés, negocio y BD en español · las **retenciones no van en la factura ni en el asiento** (solo neto a pagar) · **F1 = FESP, F20 = FEMG**.

**Compuerta de cierre (aplica a todos los sprints):** solo se cierra cuando (1) pruebas unitarias en verde, (2) pruebas de integración (Testcontainers) en verde, (3) ArchUnit pasa, (4) cada historia validada contra sus criterios de aceptación, (5) code review OK, (6) `./gradlew build` verde. Si algo falla, se corrige en el mismo sprint.

---

## Sprint 0 — Scaffold y cimientos

Trabaja sobre `PROMPT_corporate-billing-service.md`. Deja el proyecto listo para construir encima.

Tareas:
- Crea el proyecto (ya generado en Spring Initializr: Gradle Groovy, Java 21, Spring Boot 4.1.1, paquete base `com.sistemaspalacios.billing`) con las dependencias: Web, Data JPA, PostgreSQL, Validation, Flyway, Lombok, Actuator. Agrega **ArchUnit** al `build.gradle` (no viene de Initializr).
- Crea la estructura de paquetes de capas: `web`, `service`, `domain (model, vo, service)`, `repository`, `infrastructure (ordenes, siigo, file)`, `config`.
- Crea la migración `V1__esquema_inicial.sql` con el esquema completo de la sección 6 del prompt maestro (incluidas las tablas de configuración tributaria y `mapeo_cuenta_contable`).
- Configura Actuator (`/actuator/health`) y perfiles/propiedades por entorno.
- Escribe la **regla base de ArchUnit** para el orden de capas `web → service → domain/repository` y `repository → domain`.

Pruebas: el contexto de Spring levanta; `V1` aplica en un Postgres de Testcontainers; ArchUnit base pasa; `/actuator/health` responde `UP`.

Entregable: proyecto compilando, migración aplicada, esqueleto de capas y ArchUnit en verde.

---

## Sprint 1 — Dominio + reglas de cálculo (sin BD)

Implementa el corazón del cálculo como **lógica pura y testeada**, sin persistir todavía. Historias: HU-10, HU-11, HU-12, HU-13, HU-14, HU-15, HU-16, HU-18 (y el cálculo de retenciones/neto de HU-15).

Tareas:
- Modela las entidades de dominio (`Factura`, `ItemFacturable`, `ClienteCorporativo`, `SedeSuscripcion`, `NovedadOrden`, `PeriodoFacturacion`, `CuotaInstalacion`, `LoteExportacion`) y los value objects (`Dinero`, `PerfilTributario`, `ResultadoProrrateo`, `ResultadoRetenciones`, `RangoFechas`). Todavía sin anotaciones JPA.
- Implementa los servicios de dominio (`CalculadoraFacturacion`, `CalculadoraImpuestos`, `CalculadoraProrrateo`, `GeneradorAsientoContable`) con la sección 5 completa:
  - fecha de cobro = certificación (salvo observación), prorrateo por días y su excepción, cuotas de instalación, descuentos, IVA por ítem gravado.
  - **retenciones (retefuente, reteica, reteiva, estampillas) solo para el neto a pagar**, resolviendo base y porcentaje por **vigencia a la fecha del corte**, clasificación internet/software y municipio (reteica). `neto_a_pagar = valor_total − retenciones`; el valor de la factura no cambia.
  - **asiento de doble partida SIN retenciones** (ingresos por sede + IVA = cartera); rechaza si no cuadra.
  - descripción de la secuencia con el patrón `SEDE.INT.FIB.<TIPO>.<VELOCIDAD> GR <DD-DD MMM AAAA>`.

Pruebas unitarias (exhaustivas, con casos de borde): novedad a mitad de periodo (prorrateo), observación que anula prorrateo, cuota que se agota, ítem no gravado, cliente con y sin retención, **cambio de base a mitad de año (vigencias)**, reteica territorial, internet vs software, y **asiento que cuadra débitos = créditos**.

Entregable: dominio con cobertura alta; todas las reglas verdes sin arrancar Spring.

Listo
---

## Sprint 2 — Persistencia

Persiste el dominio y garantiza las invariantes en BD. Historias: HU-08, HU-09, HU-29/RF-29 (histórico por inactivación).

Tareas:
- Anota las entidades como `@Entity` (DDD-Lite: las mismas clases del dominio, sin mapper aparte) mapeadas a las tablas en español.
- Crea los repositorios Spring Data (`RepositorioFactura`, `RepositorioCliente`, `RepositorioSede`, `RepositorioNovedad`, `RepositorioPeriodo`, `RepositorioLote`, y los de configuración tributaria).
- Implementa el **índice único parcial** de factura activa por (cliente, periodo) y el **snapshot fiscal inmutable** en la cabecera.

Pruebas de integración (Testcontainers): guardar/leer agregados; el índice único rechaza dos facturas activas del mismo periodo; el snapshot no cambia al editar el cliente; la inactivación conserva histórico.

Entregable: persistencia estable con invariantes verificadas contra Postgres real.

Listo
---

## Sprint 3 — Integración con Órdenes + configuración tributaria

Puebla los maestros y deja administrable la configuración tributaria. Historias: HU-01 a HU-05, HU-17, HU-25/HU-28, HU-27.

Tareas:
- Define la interfaz `ClienteSistemaOrdenes` (abstracta: `obtenerSuscripciones(periodo)`, `obtenerNovedades(periodo)`) y su implementación HTTP con `RestClient` (base URL, credenciales, timeouts, reintentos, todo configurable). Marca como `<PENDIENTE>` el contrato concreto de endpoints.
- Implementa `ServicioOrdenes.sincronizar(periodo)` con *upsert* idempotente de clientes, sedes, cuotas y novedades; y el **importador de archivo** (`infrastructure/file`) como alternativa, con trazabilidad (crudo por línea + errores que no bloquean).
- Implementa `ServicioConfiguracionTributaria`: CRUD de conceptos, vigencias (base + % con fecha) y perfil tributario por corporativo (con override y clasificación internet/software).

Pruebas: cliente HTTP contra un **servidor mock**; upsert idempotente (reejecutar no duplica); líneas con error no bloquean; CRUD de configuración y resolución de vigencia por fecha.

Entregable: sincronización idempotente + configuración tributaria administrable.

Listo
---

## Sprint 4 — Generar factura consolidada (slice canónico)

El flujo de punta a punta que reemplaza el Excel manual. Historias: HU-06, HU-07, HU-26.

Tareas:
- Implementa `ServicioFacturacion.generarConsolidada(periodo, tipoFacturacion, tipoCorte, [nit])`: carga maestros/novedades, invoca los servicios de dominio, arma la factura consolidada por NIT, calcula IVA y el neto a pagar (retenciones aparte), persiste, y respeta el índice de unicidad.
- Expón los controladores REST: `POST /facturas/generar`, `GET /facturas`, `GET /facturas/{id}`; con DTOs, mappers y `@RestControllerAdvice` para errores.

Prueba de aceptación end-to-end: cliente con 3 sedes activas, una con alta a mitad de periodo y una con cuota pendiente → factura con cabecera, ítems por sede, prorrateo correcto, IVA, totales que cuadran, y unicidad respetada. Caso de "ya existe factura activa" devuelve error claro.

Entregable: factura consolidada correcta y consultable de extremo a extremo. **(Este es el núcleo del MVP.)**

Listo
---

## Sprint 5 — Ajustes: anulación y notas crédito

Correcciones sobre facturas emitidas. Historias: HU-19, HU-20.

Tareas:
- `ServicioFacturacion.anular(facturaId, motivo)`: marca `ANULADA` (motivo + fecha) y habilita refacturar.
- `ServicioNotaCredito.emitir(facturaId, ...)`: crea `documento_ajuste` ligado a su factura.
- Endpoints: `POST /facturas/{id}/anular`, `POST /facturas/{id}/notas-credito`.

Pruebas: anular habilita una nueva factura activa del periodo (respeta el índice único); la nota crédito queda ligada a su factura y es consultable.

Entregable: flujos de ajuste probados sin romper unicidad ni totales.

Listo
---

## Sprint 6 — Exportación a Siigo, confirmación y clasificación automática de PDFs

Produce el archivo plano, consume la carpeta compartida en el servidor y clasifica automáticamente cada factura y PDF bajo su cliente corporativo correspondiente. Historias: HU-21, HU-22, HU-23, HU-24, HU-29.

> **Arquitectura de integración con Siigo Pyme:** Siigo genera y comparte una carpeta hacia el servidor de Sistemas Palacios (ruta montada/compartida en el servidor, parametrizable vía `application.yml` / variable de entorno `SIIGO_CARPETA_COMPARTIDA_PATH`). Esta carpeta contiene tanto los **archivos planos de confirmación/consecutivos** como las **facturas oficiales en PDF**. El microservicio consume directamente dicha carpeta y automatiza la clasificación de cada comprobante asociándolo a su cliente corporativo.

Tareas:
- Define la interfaz `EmisorFacturaOficial` e implementa `ExportadorArchivoPlanoSiigo` con el layout real (asiento de doble partida, sección 9; **sin retenciones**), reemplazando el bloque `<<< FORMATO Siigo F1/F20 >>>` con el layout confirmado. F1 = comprobante código 1 (prefijo FESP), F20 = código 20 (prefijo FEMG).
- Implementa `ServicioLote.generar` (crea `lote_exportacion` con las facturas del corte) y `GET /lotes/{id}/archivo`.
- Implementa `ConsumidorCarpetaCompartidaSiigo` / `ParserPlanoSiigo`:
  - Lee desde la carpeta del servidor los archivos planos devueltos por Siigo.
  - Extrae de cada línea/registro: `consecutivo`, `tipo_comprobante`, `nit_cliente`, `cufe` y `estado_envio`.
  - Empareja con la factura del lote mediante el `nit_cliente` y `tipo_facturacion`; actualiza `numero_factura`, `cufe`, `estado_envio` y pasa el lote a `CONFIRMADO`.
- Implementa el **Motor de Clasificación Automática de Facturas y PDFs por Cliente Corporativo**:
  - **Nivel 1 (Parser por Nombre Siigo - `ParserNombrePdfSiigo`):**
    - Aplica regex sobre la convención de Siigo (`^F(?<tipo>\d{3})(?<consecutivo>\d+?)0000\.PDF$`, ej. `F001...519930000.PDF` → F1 / 51993).
    - Normaliza el consecutivo (remueve ceros a la izquierda) y consulta la factura en BD por `(tipo_facturacion, numero_factura)`.
    - Al encontrar la factura, queda **clasificada unívocamente bajo su `cliente_corporativo_id` y `nit_cliente`**.
  - **Nivel 2 (Fallback por Contenido con Apache PDFBox - `ExtractorMetadatosPdfSiigo`):**
    - Agrega dependencia `org.apache.pdfbox:pdfbox` al `build.gradle`.
    - Si el nombre no cumple la convención o no coincide, analiza el texto del PDF para extraer por expresiones regulares el **CUFE** (hash 64-96 chars) o el **NIT del cliente**.
    - Relaciona el documento con la factura en BD por CUFE o por NIT + periodo.
  - **Nivel 3 (Vinculación y Organización - `OrganizadorArchivosCliente`):**
    - Almacena la ruta en `factura.ruta_pdf`.
    - *(Opcional/configurable)* Copia o indexa el PDF en la estructura ordenada por cliente: `/storage/facturas/{nit_cliente}/{periodo}/...`.
    - Expone endpoints: `GET /facturas/{id}/pdf` (descarga individual) y `GET /clientes/{nit}/facturas/{periodo}/pdf` (consulta directa por cliente).
- Configuración y resiliencia: ruta de carpeta compartida configurable (`siigo.carpeta-compartida.path`), control de archivos en proceso/incompletos, tolerancia a fallos por PDF no reconocido y registro de trazabilidad/auditoría de archivos clasificados.

Pruebas:
- El archivo de exportación generado cuadra (Σ débitos = Σ créditos) y respeta orden/formato de columnas.
- Parsing correcto del plano de retorno de Siigo con actualización de `numero_factura`, `cufe` y estado del lote.
- Clasificación automática de PDF por nombre (`F001...0000.PDF` / `F020...0000.PDF`) asociando correctamente el PDF al cliente corporativo y su factura.
- Fallback con PDFBox: prueba de extracción de CUFE/NIT de un PDF con nombre no estándar.
- Manejo de excepciones ante carpeta inaccesible, archivos con bloqueo de lectura o PDFs sin correspondencia en BD.

Entregable: exportación del lote, consumo automático de la carpeta compartida en el servidor de Sistemas Palacios con **clasificación automática de planos y PDFs por cliente corporativo**, y endpoints de consulta/descarga listos.

Listo
---

## Sprint 7 — Estabilización y no funcionales

Endurece y deja listo para operación. Alcance: los RNF.

Tareas:
- Seguridad: credenciales/URLs fuera del código (variables/secretos), validación de entrada, logs sin datos sensibles.
- Observabilidad: logging en puntos de negocio (sincronización, factura generada, lote exportado/confirmado), métricas de Actuator.
- Resiliencia: timeouts/reintentos hacia Órdenes; el fallo de un registro no detiene el lote.
- Rendimiento: verificar con un cliente de 40+ sedes.
- Cierra pendientes con decisión registrada; documentación final; pruebas de aceptación de usuario (UAT).

Compuerta: RNF verificados y checklist de pendientes cerrado o con decisión anotada.

Listo
---

## Mapa MVP

Para el MVP que ya estás construyendo, basta: **S0 → S1 (prorrateo + IVA + asiento, deja retenciones para después) → S2 → S3 (solo importador de archivo) → S4 → S6 (solo exportar F1)**. El resto (retenciones completas, F20, ajustes, PDF, endurecimiento) entra después del MVP.
