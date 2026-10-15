# Sprint 9 — Realineación del modelo tributario y módulo de recaudo

- **Servicio:** `corporate-billing-service`
- **Paquete base:** `com.sistemaspalacios.billing`
- **Prerrequisito:** S0–S8 completados y en verde.
- **Duración estimada:** 2 semanas.
- **Motivo:** la última reunión con cartera y contabilidad **redefinió el modelo tributario**. Lo implementado en S1–S4 corresponde al modelo anterior (perfil por cliente, retenciones liquidadas al facturar). Este sprint realinea el sistema con la decisión vigente y cubre los requisitos operativos que quedaron fuera.

---

## 1. Objetivo

1. **Eliminar la configuración tributaria por cliente** y dejar un **catálogo general** de conceptos, versionado por vigencias.
2. **Mover el cálculo de retenciones del momento de facturación al momento del pago**, donde el corporativo **selecciona** qué le aplica.
3. Construir el **módulo de recaudo**: pago total o parcial, recibo de caja e interfaz de pago para Siigo.
4. Cerrar los huecos operativos: **sincronización horaria**, **filtros de búsqueda de cartera**, **notas crédito cruzadas**, **redondeo**, **total por NIC** y **clasificación de residenciales**.

> **Regla de oro que gobierna todo el sprint:** el valor legal de la factura **nunca cambia**. Las retenciones afectan únicamente el recaudo. El asiento contable de causación **no lleva retenciones** (eso ya está correcto en S6 y debe seguir así).

---

## 2. Cambio 1 — Catálogo tributario general (elimina el perfil por cliente)

**Contexto del negocio:** se propuso configurar impuestos por corporativo y **fue rechazado**. Razones dadas: muy pocos aplican el 3.5%; las entidades públicas "a veces aplican y a veces no"; y las estampillas son múltiples y variables (pro-desarrollo universidad, Senado, deportes, justicia). La decisión: **crear los impuestos una sola vez, generales para todos**, y que el cliente **elija al pagar**.

### Tareas

- **Eliminar** `ClientTaxProfileEntity` / `perfil_tributario_cliente`, su repositorio (`RepositorioPerfilTributarioCliente`) y los métodos de *override* por cliente en `TaxConfigurationService`. Migración **`V4__eliminar_perfil_tributario_cliente.sql`** que elimina la tabla (conserva un respaldo si ya hay datos productivos).
- **Simplificar `TaxProfile`**: deja de ser el perfil del cliente. Si se conserva, que represente únicamente el **conjunto de conceptos seleccionados en un pago**. Elimina `appliesWithholding` del `CorporateClient` como fuente de verdad del cálculo (puede quedar como bandera informativa).
- **Reforzar `TaxConcept` / `TaxConceptValidity`** como catálogo único:
  - Deben **coexistir varios conceptos del mismo tipo** (ReteFuente 4% servicios y 3.5% software) y **varias estampillas**, cada uno con su etiqueta visible.
  - Cada registro lleva `porcentaje`, `base_minima`, `vigente_desde`, `vigente_hasta`, `estado` y **número de versión**.
  - **Un cambio de base crea una versión nueva** y cierra la anterior con `vigente_hasta`; **nunca se sobrescribe**. El negocio reportó **tres bases distintas en un mismo año**.
  - `reteica` mantiene su carácter territorial (resolución por municipio).
- Endpoints `/api/v1/impuestos/**` quedan solo con el catálogo general (escritura restringida a `ROL_ADMINISTRADOR`, como ya está en S8).

### Pruebas

- Coexistencia de varios conceptos del mismo tipo y varias estampillas.
- Cambio de base → se crea versión nueva, la anterior queda cerrada, y la resolución por fecha devuelve la correcta en cada tramo.
- Ya no existe ninguna ruta ni servicio para configurar impuestos por cliente.

---

## 3. Cambio 2 — Retenciones al momento del pago, no al facturar

**Problema actual:** `TaxCalculator` liquida las retenciones durante `generateConsolidatedInvoice` y la `Invoice` persiste `valor_retefuente`, `valor_reteica`, `valor_reteiva`, `valor_estampillas` y `neto_a_pagar`. Al emitir la factura **aún no se sabe** qué retenciones aplicará el cliente.

### Tareas

- **Deja de calcular retenciones al generar la factura.** La factura conserva únicamente sus valores oficiales: `valor_bruto`, `valor_descuento`, `valor_subtotal`, `valor_iva`, `valor_total`.
- Migración **`V5__retenciones_al_pago.sql`**: elimina de `factura` las columnas de retenciones y `neto_a_pagar` (o márcalas como obsoletas si hay datos históricos que preservar).
- **Conserva y reubica `TaxCalculator`**: su lógica de bases, vigencias fechadas y `WithholdingDetail` es correcta y se reutiliza — pero ahora se invoca desde el módulo de recaudo, recibiendo **la lista de conceptos que el cliente seleccionó**, no un perfil del cliente.
- **Bases de cálculo (verificar que se respetan):**
  - ReteFuente, ReteICA y estampillas → sobre la **base gravable**.
  - ReteIVA → sobre el **valor del IVA**, nunca sobre el total.
  - Un concepto cuya **base mínima** supere el valor evaluado **no aplica** y se reporta como "no aplicable".
- **Redondeo:** el neto final se redondea al peso (`HALF_UP`, escala 0) y se expone la **diferencia por redondeo** como dato aparte. Las entidades públicas redondean.

### Pruebas

- La factura generada **no contiene** valores de retención.
- Liquidación con selección vacía → neto = valor total de la factura.
- ReteIVA calculado sobre el IVA (caso explícito que falle si se calcula sobre el total).
- Concepto por debajo de base mínima → no descuenta y se marca no aplicable.
- El valor oficial de la factura permanece idéntico antes y después de liquidar.
- El asiento de causación sigue sin líneas de retención (regresión de S6).

---

## 4. Cambio 3 — Módulo de recaudo (`payment`)

Nuevo módulo de aplicación. Migración **`V6__recaudo.sql`**.

### Modelo

- **`Payment` (`pago`)**: id, cliente, fecha, medio de pago, cuenta de banco, valor bruto pagado, total retenciones, **valor redondeo**, neto pagado, número de transacción, usuario que registra, estado.
- **`PaymentInvoice` (`pago_factura`)**: relación pago ↔ factura(s) cruzada(s) con el valor aplicado a cada una. **Reutiliza la tabla `pago_factura` que ya estaba prevista en `V1` y quedó fuera de alcance.**
- **`PaymentWithholding` (`retencion_pago`)**: por cada retención aplicada guarda concepto, **porcentaje exacto**, **base mínima exacta**, **base sobre la que se calculó**, valor retenido y **`vigencia_concepto_id` + número de versión**.

> Requisito textual del negocio: *"se tiene que guardar el histórico de que esa factura se pagó con esa versión de ese porcentaje"*. Este registro es **inmutable**: un cambio posterior del catálogo **no altera** los pagos ya registrados.

### Servicios de aplicación

- `PaymentService.simulate(nit, invoiceIds, selectedConceptIds)` — devuelve el desglose sin persistir: valor de factura(s), evaluación de cada concepto (aplica / no aplica y por qué), total retenciones, redondeo y neto.
- `PaymentService.register(...)` — persiste el pago, cruza las facturas, congela las versiones aplicadas y cambia el estado de las facturas pagadas a `PAGADA`.
- **Pago parcial:** un cliente con varias facturas pendientes puede pagar solo algunas. Las no pagadas **siguen pendientes**, y la respuesta incluye el aviso correspondiente (*"Gracias por su pago. Recuerde que su factura de septiembre continúa pendiente."*).
- `PaymentService.getReceipt(paymentId)` — **recibo de caja** (HTML imprimible, reutilizando el enfoque de `renderInvoiceHtml`).
- `PaymentService.exportPaymentFile(paymentId | batch)` — **interfaz de pago para Siigo**: archivo plano con el cruce factura↔pago, **cuenta de banco parametrizable** y las **cuentas contables de los impuestos retenidos**. Reutiliza el estilo de `SiigoFlatFileExporter` y valida cuadre.
- Estado de cartera por cliente: al día / con saldo, número de facturas pendientes y total adeudado.

### Endpoints (`/api/v1/pagos`)

- `POST /simular` · `POST /` (registrar) · `GET /{id}` · `GET /{id}/recibo` · `GET /{id}/interfaz-siigo` · `GET /cartera/{nit}`.
- Autorización: `ROL_ANALISTA` y `ROL_ADMINISTRADOR` registran; `ROL_PASARELA` solo simula y consulta cartera de **su** NIT (coherente con HU-33).

### Pruebas

- Simulación con y sin conceptos seleccionados.
- Pago parcial: 2 facturas pendientes, se paga 1 → la otra sigue pendiente con su aviso.
- El histórico conserva porcentaje, base y versión; **modificar el catálogo después no altera el pago registrado**.
- Recibo de caja e interfaz de Siigo generados y descargables.

---

## 5. Cambio 4 — Sincronización horaria desde la carpeta compartida

**Contexto:** no hay conexión en vivo con Siigo. Todo se lee de la carpeta compartida (planos **y** PDFs) y el consumo debe ser **cada hora**. Además, la facturación **no ocurre solo a primeros de mes**: van surgiendo facturas a lo largo del mes.

### Tareas

- **`ScheduledSiigoSyncJob`** con `@Scheduled` (cron configurable, por defecto horario) que ejecuta: confirmación de lotes desde la carpeta (`confirmBatchFromSharedFolder`) y clasificación de PDFs (`processSharedFolderPdfs`).
- **`SyncStatus`** persistido: última ejecución, duración, facturas nuevas detectadas, notas crédito cruzadas, PDFs clasificados, errores y próxima ejecución. Expuesto en `GET /api/v1/sincronizacion/estado`.
- **Ejecución manual** `POST /api/v1/sincronizacion/ahora` para facturas urgentes que no pueden esperar el ciclo.
- Ejecución **no reentrante** (si el ciclo anterior sigue corriendo, no se solapa) y tolerante a fallos: un archivo problemático no detiene el resto.

### Pruebas

- El job corre según el cron y actualiza `SyncStatus`.
- Ejecución manual funciona y no se solapa con la programada.
- Un archivo corrupto no aborta el ciclo.

---

## 6. Cambio 5 — Consultas y clasificación de cartera

### Tareas

- **Filtros de búsqueda** en `GET /api/v1/facturas` — requisito explícito de cartera: **razón social (nombre)**, **NIT**, **rango de fechas** y **número de factura**, combinables, con paginación y orden.
- **Fechas por factura**: exponer `fecha_emision` y `fecha_vencimiento` **de cada factura**. No hay una fecha única del periodo: unas vencen el 16 y otras corridas al 25.
- **Nombre no disponible**: cuando el archivo de origen no trae la razón social, devolver el NIT con una marca explícita (`nombreDisponible=false`). **No inventar nombres.**
- **Notas crédito cruzadas**: la factura expone `valor_nota_credito` y `saldo_pendiente`; las facturas cuyo saldo quedó en cero **no aparecen como pendientes** en cartera ni en la liquidación de pago. Riesgo señalado expresamente en la reunión.
- **Total facturado por NIC** (mayoristas/revendedores): `GET /api/v1/reportes/nic?periodo=` agrupando por `nic`.
- **Clasificación de línea**: distinguir **F1 (FESP)**, **F20 (FEMG)** y marca **Enlaces** (dentro de F1, fuera del módulo SPE). Marcar las **residenciales masivas** que llegan en el archivo de cartera y **excluirlas por defecto** de las consultas corporativas, con filtro para incluirlas.

### Pruebas

- Cada filtro por separado y combinados.
- Factura con nota crédito total → no aparece como pendiente.
- Reporte por NIC agrupa correctamente.
- Residenciales excluidas por defecto e incluidas con el filtro.

---

## 7. Corrección — Formato de descripción y prorrateo

- **Descripción de línea:** unificar con el formato **real** observado en las facturas de Siigo: `LA CRUZ.INT.FIB.DEDI.1.65 GB GR 01-31 AGT 2026`, `PLAZUELAS INT.FIB.DEDI 200MB GR 12-31 AGT 2026`, `NT.FIB.CORP.200MB GR 01-31 AGT 2026`. El Sprint 1 documenta un formato distinto (`PASTO.CORP.INTERNET DEDICADO 50M...`) que **no coincide** con el real. Corregir `formatItemDescription()` y su prueba.
- **Prorrateo:** el ejemplo real muestra `12-31` sobre un mes de **31 días**, mientras la implementación usa **base 30 comercial**. **Validar con contabilidad** cuál criterio rige y ajustar `ProrationCalculator` en consecuencia. `<PENDIENTE: confirmar criterio de prorrateo>`.

---

## 8. Correcciones de higiene

- **Retirar el perfil `local` con H2**: el esquema usa índice único **parcial**, `JSONB`, enums nativos y `gen_random_uuid()`, que H2 no reproduce fielmente. Dejar **Testcontainers/PostgreSQL como única fuente de verdad** para pruebas.
- **Unificar nomenclatura** en documentación y código: `item_facturable` (no `item_factura`) y los estados de factura `BORRADOR | CONSOLIDADA | PAGADA | ANULADA`.
- **Corregir la numeración de HU** para que coincida con el documento de requerimientos (hoy hay corrimiento: la clasificación de PDFs quedó como HU-24, que originalmente era el total por NIC) y actualizar la matriz de trazabilidad.
- **Completar el README**: falta S6 en la tabla de sprints.
- **Dockerización**: incorporar `Dockerfile` multietapa (JDK build → JRE runtime, usuario no root, `HEALTHCHECK` sobre `/actuator/health`), `docker-compose.yml` con PostgreSQL (volumen persistente y `depends_on: service_healthy`), `.env.example`, `.dockerignore` y `DOCKER.md`. **Monta la carpeta compartida de Siigo como bind mount de solo lectura** — es el punto que más falla al contenerizar.

---

## 9. Compuerta de cierre del Sprint 9

Además de la compuerta estándar (unitarias, integración con Testcontainers, ArchUnit, HU validadas, revisión de código, `./gradlew build`):

- [ ] No existe ninguna ruta, servicio ni tabla de configuración tributaria **por cliente**.
- [ ] La factura generada **no contiene** valores de retención ni `neto_a_pagar`.
- [ ] Las retenciones se calculan **solo al liquidar un pago**, con los conceptos **seleccionados**, y ReteIVA sobre el IVA.
- [ ] Cada pago guarda **porcentaje, base y versión** aplicados, y es inmutable ante cambios posteriores del catálogo.
- [ ] Pago parcial deja las demás facturas pendientes.
- [ ] Recibo de caja e interfaz de pago para Siigo descargables.
- [ ] Sincronización horaria activa, con estado consultable y ejecución manual.
- [ ] Búsqueda por nombre, NIT, rango de fechas y número de factura operativa.
- [ ] Facturas con nota crédito total no figuran como pendientes.
- [ ] El asiento contable sigue **sin retenciones** y cuadrando (regresión de S6 en verde).
- [ ] El servicio levanta con `docker compose up -d --build` desde cero.

---

## 10. Historias de usuario del sprint

| ID | Historia | Criterios clave |
|---|---|---|
| **HU-50** | Como administrador, quiero un catálogo general de impuestos versionado, para no configurarlos cliente por cliente. | Coexisten varios conceptos del mismo tipo y varias estampillas; cambiar la base crea versión nueva y cierra la anterior. |
| **HU-51** | Como corporativo, quiero elegir al pagar qué retenciones me aplican, para pagar el neto correcto. | Selección opcional y múltiple; se indica cuáles no aplican por base mínima; el valor de la factura no cambia. |
| **HU-52** | Como sistema, quiero calcular cada retención sobre su base correcta, para no cobrar de más ni de menos. | ReteFuente/ReteICA/estampillas sobre base gravable; ReteIVA sobre el IVA; neto redondeado al peso con diferencia visible. |
| **HU-53** | Como corporativo con varias facturas, quiero pagar solo algunas, para manejar mi flujo de caja. | Las pagadas cambian a PAGADA; las demás siguen pendientes con aviso. |
| **HU-54** | Como administrador, quiero saber con qué versión se liquidó cada pago, para auditar. | Se guardan porcentaje, base y versión; editar el catálogo después no altera pagos registrados. |
| **HU-55** | Como analista, quiero el recibo de caja y la interfaz de pago, para cruzar en contabilidad. | Ambos descargables, con cuenta de banco y cuentas de impuestos. |
| **HU-56** | Como analista, quiero que el sistema lea Siigo cada hora y poder forzarlo, para trabajar con datos al día. | Job horario no reentrante; estado consultable; ejecución manual disponible. |
| **HU-57** | Como analista de cartera, quiero buscar por nombre, NIT, fechas y número, para encontrar rápido. | Filtros combinables y paginados; sin nombre se marca como no disponible. |
| **HU-58** | Como analista, quiero que las facturas con nota crédito no figuren como pendientes, para no cobrar de más. | La factura muestra saldo real; con saldo cero sale de pendientes. |
| **HU-59** | Como analista, quiero el total facturado por NIC, para revisar mayoristas sin hacerlo uno por uno. | Reporte agrupado por NIC y periodo. |
| **HU-60** | Como analista, quiero separar las residenciales masivas, para que no se mezclen con corporativos. | Se marcan y se excluyen por defecto, con filtro para incluirlas. |
