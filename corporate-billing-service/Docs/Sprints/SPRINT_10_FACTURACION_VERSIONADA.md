# Sprint 10 — Facturación versionada y regeneración desde novedades

- **Servicio:** `corporate-billing-service`
- **Paquete base:** `com.sistemaspalacios.billing`
- **Prerrequisito:** S0–S9 completados y en verde.
- **Duración estimada:** 2 semanas.
- **Estado:** **COMPLETADO**
- **Motivo:** la facturación no ocurre en un solo instante del mes. Siigo devuelve la factura oficial a la carpeta compartida, y después siguen llegando novedades del sistema de órdenes **del mismo periodo**. Hasta S9 la única forma de reflejarlas era **anular y volver a generar**, lo que producía una factura nueva sin ningún vínculo con la anterior: el histórico se perdía y la trazabilidad de la refacturación quedaba en cero.

---

## 1. Objetivo

1. Permitir **regenerar** la factura de un cliente para un periodo creando una **nueva versión**, en lugar de anular y empezar de cero.
2. **Conservar íntegra la versión anterior** como histórico inmutable, encadenada a la que la sustituye y con su PDF oficial preservado.
3. Hacer que la nueva versión **acumule todo el mes**: todas las sedes activas y todas las novedades del periodo, de modo que el cliente reciba un único valor a pagar por su NIT.
4. Cerrar la ausencia de **trazabilidad novedad → factura**, que permitía aplicar la misma novedad varias veces sin control.
5. Evitar que la ingesta horaria de la carpeta compartida **sobrescriba** el PDF oficial sin dejar rastro.

> **Regla de oro del sprint:** una factura emitida **nunca se modifica en sitio**. Regenerar significa crear una versión nueva y cerrar la anterior. El valor legal de cada versión queda congelado en su propia fila.

---

## 2. Cambio 1 — Estado `REEMPLAZADA` y cadena de versiones

**Contexto del negocio:** el índice único parcial `uq_factura_cliente_periodo_activa` (S2, HU-08) garantiza una sola factura activa por cliente y periodo. Eso es correcto y debe seguir siendo cierto, pero su predicado original (`estado != 'ANULADA'`) impedía que una versión histórica conviviera con la vigente.

### Tareas ejecutadas

- **`InvoiceStatus.SUPERSEDED`** mapeado a `REEMPLAZADA`, con `isCurrent()` que refleja exactamente el predicado del índice. Los estados vigentes son `BORRADOR`, `CONSOLIDADA` y `PAGADA`; los no vigentes, `ANULADA` y `REEMPLAZADA`.
- Migración **`V8__estado_factura_reemplazada.sql`** que contiene **exclusivamente** el `ALTER TYPE estado_factura ADD VALUE IF NOT EXISTS 'REEMPLAZADA'`.

  > **Motivo del archivo aislado:** PostgreSQL no permite *usar* un valor de enum en la misma transacción que lo agrega (`unsafe use of new value of enum type`). Flyway ejecuta una transacción por migración, así que todo el DDL que referencia `'REEMPLAZADA'` vive en `V9`. **No agregar nada más a `V8`.**

- Migración **`V9__factura_versionada.sql`**:
  - Columnas en `factura`: `numero_version`, `factura_origen_id`, `factura_anterior_id`, `reemplazada_por_id`, `reemplazada_en`, `motivo_regeneracion`, `regenerado_por`.
  - *Backfill*: toda factura preexistente queda como raíz de su propia cadena (`factura_origen_id = id`), y la columna pasa a `NOT NULL`.
  - `CHECK chk_factura_cadena_coherente`: la versión 1 no puede tener antecesora y de la 2 en adelante es obligatoria. `CHECK chk_factura_no_autoreemplazo`.
  - **`uq_factura_cliente_periodo_activa` recreado** con el mismo nombre (lo referencian el servicio y `PersistenciaInvariantesIntegrationTest`) y predicado nuevo: `WHERE estado <> 'ANULADA' AND estado <> 'REEMPLAZADA'`.
  - `uq_factura_cadena_version (factura_origen_id, numero_version)`: no se repiten versiones dentro de una cadena.
  - **`fk_factura_reemplazada_por` declarada `DEFERRABLE INITIALLY DEFERRED`.**

  > **Por qué diferida:** la referencia es circular en el tiempo. Al regenerar, la versión anterior debe marcarse como reemplazada **antes** de insertar la nueva —si no, las dos serían vigentes a la vez y violarían el índice único—, pero apunta a una fila que todavía no existe. Con la verificación diferida al *commit* ambas condiciones se cumplen.

- **Dominio en `Invoice`:** `markAsNextVersionOf(previous, reason)` hereda la raíz de la cadena y valida mismo cliente y mismo periodo; `supersede(replacementId, reason, user)` cierra la versión; `isSuperseded()`, `isCurrentVersion()`, `isFirstVersion()`, `canBeRegenerated()` y `assertCanBeRegenerated()`.
- `voidInvoice()` **rechaza** anular una versión ya reemplazada (es histórico inmutable) y `canBeAdjusted()` excluye `SUPERSEDED`, de modo que no se emitan notas crédito contra versiones históricas.

### Pruebas

`InvoiceVersioningTest` (11 casos) y `PersistenciaInvariantesIntegrationTest` (regresión de HU-08):

- Una factura nueva es la versión 1, raíz de su propia cadena y sin antecesora.
- Al regenerar, la v2 hereda la raíz y la v1 queda `REEMPLAZADA` apuntando a la v2, con su valor total intacto.
- Una versión reemplazada no se puede regenerar, anular ni ajustar por nota crédito.
- No se puede versionar sobre una factura de otro cliente o de otro periodo, ni sobre sí misma.
- El índice único sigue rechazando dos facturas **vigentes** del mismo cliente y periodo.

---

## 3. Cambio 2 — Regeneración (`BillingService.regenerateInvoice`)

### Comportamiento

`regenerateInvoice(invoiceId, reason, confirmTransmittedCufe)` ejecuta, en una sola transacción:

1. **Guardas de estado.** Se rechaza si la factura está anulada, ya fue reemplazada, o **ya fue pagada** — en ese último caso el ajuste corresponde a una nota crédito, no a una regeneración.
2. **Guarda fiscal.** Si la factura ya tiene **CUFE**, fue transmitida a la DIAN. Regenerarla altera un documento electrónico radicado, así que la operación **se rechaza** salvo que el operador lo confirme explícitamente con `confirmarCufeTransmitido: true`. Ambos rechazos viajan como `FacturaNoRegenerableException` (`REASON_ESTADO` / `REASON_CUFE_TRANSMITIDO`) → **HTTP 409** con el campo `requiereConfirmacion`.
3. **Recarga del contexto completo del periodo**: cliente, periodo, sedes facturables, **todas** las novedades del periodo y las vigencias tributarias. La nueva versión no es un delta: es el consolidado del mes recalculado desde cero.
4. **Liberación de cuotas de instalación.** Las cuotas que consumió la versión reemplazada se devuelven al *pool* de pendientes con `InstallationFee.rollbackBilledInstallments(n)` antes de recalcular. Sin esto la nueva versión avanzaría el contador por segunda vez y **el cliente pagaría dos veces la misma cuota**.
5. **Recálculo** vía `BillingCalculator`, herencia del indicador `es_residencial` y de los campos extra (EAV) de la versión anterior, numeración y transición a `CONSOLIDADA`.
6. **Archivado del PDF anterior** y cierre de la versión previa (`supersede`) **antes** de insertar la nueva, por el orden que exige el índice único.
7. **Marcado de novedades** como facturadas contra la nueva versión.

> **Nota deliberada:** la nueva versión **no hereda `ruta_pdf`**. Es un documento fiscal distinto y su PDF oficial llegará de Siigo en el siguiente ciclo de sincronización. Hasta entonces `GET /{id}/pdf` sirve la representación gráfica renderizada, que es el comportamiento correcto.

### Consultas de apoyo

- `getInvoiceVersions(invoiceId)` — cadena completa ordenada de la versión 1 a la vigente.
- `getCurrentInvoiceForClientPeriod(nit, periodCode)` — la **única** factura vigente del cliente para el periodo: la que debe pagar.
- `RepositorioFactura.findCurrentByClientAndPeriod(...)` reemplaza a `findActiveByClientAndPeriod(...)`, que queda `@Deprecated` porque el significado de "activa" cambió: ahora también excluye las reemplazadas. La constante `NON_CURRENT_STATUSES` mantiene el código alineado con el predicado del índice.
- `RepositorioFactura.findByClientNitAndPeriodId(...)` pasa de `Optional` a `List`: con versionado, un mismo NIT y periodo tienen varias filas.

### Fachada en español

`ServicioFacturacion` expone `regenerar(...)`, `versiones(...)` y `facturaVigente(...)`.

---

## 4. Cambio 3 — Histórico del PDF oficial

**Problema:** `SiigoPdfImporterService.linkPdfToInvoice` hacía `setPdfPath(...)` + `save(...)`. Sobrescribía la ruta anterior sin dejar rastro. Y como `LocalSiigoSharedFolderReader` no mueve ni marca los archivos procesados, el job horario volvía a ver los mismos PDFs y reescribía la columna **en cada ciclo, indefinidamente**.

### Tareas ejecutadas

- Tabla **`factura_pdf_historico`** (`V9`) y entidad `InvoicePdfHistory` con `snapshotOf(invoice, origin, reason, user)`. Registra ruta, nombre, **hash SHA-256**, tamaño, número de factura, CUFE, origen (`CARPETA_COMPARTIDA_SIIGO` | `REGENERACION_VERSION`), motivo y auditoría.
- `linkPdfToInvoice` ahora es **idempotente**: si la ruta no cambió **no toca la factura**, y si cambió archiva el PDF saliente antes de sobrescribir. Esto corta el reprocesamiento horaria y preserva el PDF de cada versión.
- El hash permite detectar si un PDF cambió realmente de contenido entre ciclos, no solo de nombre.
- **Emparejamiento por NIT corregido.** El *fallback* tomaba `byNit.get(0)`, la primera factura de ese NIT sin filtrar por periodo ni estado, así que el PDF podía colgarse de la factura equivocada y repetir el error cada hora. Ahora filtra por `isCurrentVersion()` y toma la emisión más reciente. El emparejamiento por consecutivo o por CUFE, que identifican una versión concreta, sí conserva las versiones reemplazadas como dueñas legítimas de su PDF.

---

## 5. Cambio 4 — Trazabilidad novedad → factura

**Problema:** `novedad_orden` no tenía ninguna marca de facturación. Nada impedía aplicar la misma novedad en dos emisiones distintas, y no había forma de auditar qué novedad alimentó qué línea más allá de `item_facturable.sede_suscripcion_id`.

### Tareas ejecutadas

- Columnas `factura_id` y `facturado_en` en `novedad_orden` (`V9`), con índices parciales para pendientes y facturadas.
- `OrderUpdate.markAsBilled(invoiceId)` / `isBilled()` / `releaseFromInvoice()`.
- `generateConsolidated` y `regenerateInvoice` marcan las novedades consumidas contra la versión que las facturó.
- **`voidInvoice` libera las novedades** (`RepositorioNovedad.releaseByInvoiceId`): al dejar de ser vigente la factura, sus novedades vuelven al *pool* de pendientes para que una emisión posterior del periodo las recoja.
- Consultas nuevas: `findByPeriodIdAndInvoiceIdIsNull`, `findByInvoiceId`, `findPendingForInvoice`.

---

## 6. Cambio 5 — Superficie REST

| Método | Ruta | Roles | Respuesta |
|---|---|---|---|
| `POST` | `/api/v1/facturas/{id}/regenerar` | `ROL_ANALISTA`, `ROL_ADMINISTRADOR` | **201** con `facturaVigente`, `versionAnterior` y `diferenciaValorTotal` |
| `GET` | `/api/v1/facturas/{id}/versiones` | `ROL_ANALISTA`, `ROL_ADMINISTRADOR` | Cadena de `FacturaVersionDto` con la diferencia de valor entre versiones |
| `GET` | `/api/v1/facturas/clientes/{nit}/periodos/{periodo}/vigente` | + `ROL_PASARELA` | `FacturaDetalleDto` de la factura vigente |

- `RegenerarFacturaRequest(motivo, confirmarCufeTransmitido)` — el motivo es obligatorio y queda auditado en `motivo_regeneracion`.
- `FacturaResumenDto` incorpora `version` y `vigente`, de modo que el portal distinga a simple vista la versión que se paga de las históricas.
- `GlobalExceptionHandler` mapea `FacturaNoRegenerableException` a **409** incluyendo el código de motivo.

---

## 7. Compuerta de cierre del Sprint 10

Además de la compuerta estándar (unitarias, integración con Testcontainers, ArchUnit, HU validadas, revisión de código, `./gradlew build`):

- [x] Regenerar una factura crea una versión nueva y deja la anterior en `REEMPLAZADA`, no la borra ni la anula.
- [x] La versión anterior conserva su valor total, su número y su PDF oficial, y apunta a la que la sustituye.
- [x] Solo existe **una** factura vigente por cliente y periodo, garantizado por el índice parcial recreado.
- [x] La nueva versión acumula todas las sedes y novedades del periodo, no un delta.
- [x] Las cuotas de instalación no se cobran dos veces al regenerar.
- [x] Regenerar una factura con CUFE exige confirmación explícita; una factura pagada no se regenera.
- [x] El PDF que se sobrescribe queda archivado con su hash en `factura_pdf_historico`.
- [x] El ciclo horario no reescribe `ruta_pdf` cuando el archivo no cambió.
- [x] Las novedades quedan atadas a la versión que las facturó y se liberan al anular.
- [x] `PersistenciaInvariantesIntegrationTest` y `FacturacionConsolidadaAcceptanceTest` en verde (regresión de HU-08 y HU-06).

**Resultado de la corrida de cierre:** `130 tests, BUILD SUCCESSFUL`, migraciones `V1`→`V10` aplicadas contra PostgreSQL 16 vía Testcontainers.

---

## 8. Historias de usuario del sprint

| ID | Historia | Criterios clave |
|---|---|---|
| **HU-61** | Como analista, quiero regenerar la factura del mes cuando llegan novedades nuevas, para que el cliente pague el valor correcto sin esperar al mes siguiente. | Se crea una versión nueva vigente; la anterior queda `REEMPLAZADA`; la respuesta muestra la diferencia de valor. |
| **HU-62** | Como auditor, quiero que la factura anterior se conserve completa, para saber qué se le cobró al cliente en cada revisión. | La versión histórica mantiene valor, número, ítems y PDF; queda encadenada a su reemplazo con motivo y usuario. |
| **HU-63** | Como corporativo, quiero una sola factura por mes con todas mis sedes y suscripciones activas, para pagar un único valor asociado a mi NIT. | Una única factura vigente por cliente y periodo; un ítem por sede activa más las cuotas de instalación. |
| **HU-64** | Como administrador, quiero que no se pueda alterar una factura ya transmitida a la DIAN sin autorización, para no romper la trazabilidad fiscal. | Con CUFE presente la regeneración se rechaza con 409 salvo confirmación explícita; una factura pagada nunca se regenera. |
| **HU-65** | Como analista, quiero que el PDF oficial anterior no se pierda al llegar uno nuevo, para poder recuperar el documento de cada versión. | Cada PDF sobrescrito queda en `factura_pdf_historico` con hash y motivo; si el archivo no cambió, la factura no se toca. |
| **HU-66** | Como administrador, quiero saber qué novedades alimentaron cada versión de la factura, para auditar el origen de cada cobro. | `novedad_orden.factura_id` registra la versión que la consumió; anular la factura libera sus novedades. |
