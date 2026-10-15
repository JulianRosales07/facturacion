# Sprint 12 — Robustecimiento de la ingesta y deuda técnica identificada

- **Servicio:** `corporate-billing-service`
- **Paquete base:** `com.sistemaspalacios.billing`
- **Prerrequisito:** S10 y S11 completados.
- **Duración estimada:** 2 semanas.
- **Estado:** **PENDIENTE — NO IMPLEMENTADO**
- **Motivo:** durante S10 y S11 se auditó el flujo completo carpeta compartida → novedades → factura → cartera. Lo que se corrigió está documentado en esos sprints. **Este documento registra lo que se encontró y NO se corrigió**, con su ubicación exacta y su riesgo, para que sea una decisión consciente y no un hallazgo sorpresa en producción.

> Nada de lo descrito aquí está implementado. Las casillas de la compuerta de cierre están intencionalmente sin marcar.

---

## 1. Riesgo alto — La carpeta compartida se reprocesa indefinidamente

**Ubicación:** `LocalSiigoSharedFolderReader.listPendingPdfFiles()` y `listConfirmationFlatFiles()`.

El nombre `listPending` es engañoso: el método filtra por extensión y devuelve **todos** los archivos del directorio. No mueve, no renombra, no borra, no marca nada como procesado. El job horario (`ScheduledSiigoSyncJob`, cron por defecto `0 0 * * * *`) vuelve a leer los mismos archivos en cada ciclo, para siempre.

S10 mitigó el síntoma más grave —`linkPdfToInvoice` ya no reescribe `ruta_pdf` cuando la ruta no cambió— pero el trabajo sigue repitiéndose y la carpeta crece sin límite.

### Tareas propuestas

- Calcular el **hash SHA-256 del archivo al entrar** (la utilidad ya existe en `SiigoPdfImporterService.sha256Of`) y llevar un registro de archivos ya ingeridos para saltarlos sin abrirlos.
- Mover los archivos procesados a `procesados/` y los fallidos a `error/`, con la carpeta de origen montada en modo lectura-escritura. Hoy `docker-compose.yml` la monta **solo lectura** (`:ro`), lo que impide mover archivos: **decidir si se cambia el montaje o si el control de procesados vive únicamente en base de datos.**
- **Auditoría de PDFs no emparejados.** Hoy un PDF que no encaja con ninguna factura solo genera un `log.warn` y aparece en el `PdfProcessingSummary` de la respuesta HTTP. No se persiste en ninguna tabla, así que nadie se entera si el archivo llegó y quedó huérfano. `ImportedFile` / `ImportedFileLine` **no** sirven: pertenecen al flujo de ingesta de órdenes (`FileOrderImporter`), no al de PDFs de Siigo.

---

## 2. Riesgo alto — Numeración de facturas

**Ubicación:** `BillingServiceImpl.nextInvoiceNumber()` y el bucle de `generateConsolidated`.

```java
long seq = INVOICE_NUMBER_BASE + (++currentCount);   // INVOICE_NUMBER_BASE = 51950
```

`currentCount` viene de `repositorioFactura.count()`, que cuenta **todas** las filas: anuladas y reemplazadas incluidas. Con el versionado de S10 esto empeoró, porque cada regeneración agrega una fila más y el consecutivo salta.

Problemas concretos:

- No hay **índice único** sobre `factura.numero_factura` (es `VARCHAR(30)` *nullable*), así que la base no impide duplicados.
- No hay secuencia de PostgreSQL: dos hilos o dos instancias concurrentes pueden calcular el mismo número.
- Si se borra una fila, el consecutivo retrocede y colisiona.

### Tareas propuestas

- Usar una **secuencia de PostgreSQL** por prefijo (`FESP`, `FEMG`) en lugar de `count()`.
- Agregar índice único parcial sobre `numero_factura` donde no sea nulo.
- Definir si el número es **provisional** hasta que Siigo confirme el consecutivo oficial (hoy `BatchService.confirmBatch` lo sobrescribe) y, si lo es, nombrarlo como tal en el modelo para que nadie lo confunda con el número fiscal.

---

## 3. Riesgo medio — El job de sincronización no es apto para más de una instancia

**Ubicación:** `ScheduledSiigoSyncJob.isRunning` (`AtomicBoolean`).

El guardia de no reentrada vive **en memoria del proceso**. Con dos instancias del servicio corriendo, ambas ejecutan el ciclo en paralelo sobre la misma carpeta y la misma base.

Además, las **ejecuciones omitidas se registran como `FALLIDA`** (`skipped.fail("Ejecución omitida…")`), lo que ensucia cualquier métrica o alerta basada en ciclos fallidos. Con la severidad introducida en S11 ya existe el estado `CON_ADVERTENCIAS`, que encaja mejor, o convendría un estado `OMITIDA` propio.

### Tareas propuestas

- Lock distribuido en base de datos (ShedLock o un `SELECT ... FOR UPDATE` sobre una fila de control).
- Reclasificar las ejecuciones omitidas fuera de `FALLIDA`.

---

## 4. Riesgo medio — Confirmación de lotes desde plano

**Ubicación:** `BatchService.confirmBatchFromSharedFolder(batchId)`.

Lee **todos** los planos de confirmación de la carpeta, los agrega en una sola lista y los aplica al lote que se está confirmando, **sin comprobar que el plano corresponda a ese lote**. Si hay varios lotes en estado `GENERADO` y varios planos en la carpeta, el cruce puede aplicar consecutivos y CUFE al lote equivocado.

`confirmBatch` además **sobrescribe** `numero_factura`, `cufe` y `estado_envio` de la factura sin guardar el valor previo. El histórico de PDF introducido en S10 cubre `ruta_pdf`, pero no estos tres campos.

### Tareas propuestas

- Que el plano declare a qué lote pertenece y validar la correspondencia antes de aplicar.
- Extender el histórico a los cambios de `numero_factura`, `cufe` y `estado_envio`, o registrarlos en una bitácora de confirmación.

---

## 5. Riesgo medio — Cuotas de instalación al anular

**Ubicación:** `BillingServiceImpl.voidInvoice`.

S10 añadió `rollbackInstallationFees` para la **regeneración**, y `voidInvoice` libera las novedades. Pero **anular no libera las cuotas de instalación**. Por lo tanto el camino antiguo *anular + generar de nuevo* sigue avanzando el contador de cuotas dos veces y el cliente paga dos veces la misma cuota.

Se dejó fuera de S10 deliberadamente para no alterar el comportamiento de anulación, que tiene pruebas de aceptación propias (`AjustesYNotasCreditoIntegrationTest`). Con `/regenerar` disponible el camino antiguo debería ser excepcional, pero sigue siendo alcanzable.

### Tareas propuestas

- Aplicar el mismo *rollback* de cuotas en `voidInvoice`, con pruebas de regresión sobre los tests de anulación existentes.
- Evaluar si `POST /{id}/anular` debe advertir cuando existan cuotas consumidas por la factura que se anula.

---

## 6. Riesgo medio — Notas crédito acumuladas

**Ubicación:** `CreditNoteServiceImpl`, validación del valor neto.

```java
if (adjustmentDocument.getNetAmount().isGreaterThan(invoice.getTotalAmount())) { ... }
```

La validación compara **cada nota crédito por separado** contra el total de la factura. No acumula las notas ya aplicadas, así que **dos notas parciales pueden superar entre las dos el valor de la factura** y dejar un saldo pendiente negativo.

`getPendingBalance()` protege el resultado devolviendo cero cuando el saldo es negativo, así que el síntoma no se ve en cartera, pero el dato queda inconsistente.

Relacionado: el enum `tipo_documento_ajuste` y la tabla `documento_ajuste` soportan `NOTA_DEBITO`, pero **no hay servicio ni endpoint que emita notas débito**; `CreditNoteServiceImpl` fija siempre `CREDIT_NOTE`.

### Tareas propuestas

- Validar contra la suma de notas crédito ya aplicadas.
- Decidir si se implementa la nota débito o se retira del modelo.

---

## 7. Riesgo medio — Tarifa de apertura sin fuente en el primer mes

**Ubicación:** `BillingServiceImpl.resolveOpeningRates` (nivel 3).

Limitación documentada en S11 §8. Si un `CAMBIO_PLAN` llega antes de la primera emisión del mes y el cliente no tiene factura del mes anterior, no hay fuente para la tarifa vigente el día 1 y se cobra el mes completo a la tarifa nueva.

### Tarea propuesta

Pedir al equipo del sistema de órdenes que la novedad de cambio de plan incluya la **tarifa anterior**, y agregar `novedad_orden.valor_anterior` para persistirla. Es la única vía de cierre definitiva: ninguna reconstrucción interna puede inventar un dato que el sistema nunca recibió.

---

## 8. Riesgo bajo — Rendimiento de la clasificación de PDFs

**Ubicación:** `SiigoPdfImporterService.findInvoiceByConsecutive` y `findInvoiceByMetadata`.

Ambos hacen `repositorioFactura.findAll()` y filtran en memoria con *streams*, **una vez por cada PDF de la carpeta**. El costo crece linealmente con el volumen de facturas multiplicado por el volumen de archivos, y el versionado de S10 aumenta el número de filas.

### Tarea propuesta

Consultas indexadas por número de factura y por tipo de facturación. `findByCufe` ya es una consulta directa y sirve de modelo.

---

## 9. Riesgo bajo — Validación de periodo cerrado

**Ubicación:** `BillingServiceImpl.generateConsolidated`.

Resuelve el periodo por código pero **no valida que esté `ABIERTO`**. `BillingPeriod` tiene `estado`, `isOpen()` y `close()`, y `RepositorioPeriodo.findOpenPeriods()` existe, pero nada impide facturar contra un periodo cerrado. Lo mismo aplica a `regenerateInvoice`.

### Tarea propuesta

Decidir la regla de negocio —¿se puede regenerar una factura de un periodo ya cerrado contablemente?— y hacerla explícita en el servicio.

---

## 10. Riesgo bajo — Datos fijos en la representación de la factura

**Ubicaciones:**

- `InvoiceController.getInvoiceById` y `listInvoices` pasan el periodo **hardcodeado** `"2026-09"` al DTO cuando no se envía filtro. Los endpoints de versiones y de factura vigente introducidos en S10 no tienen este problema, pero el detalle clásico sí.
- `FacturaDetalleDto.fromDomain` rellena con valores fijos ciudad, teléfono, estrato, vendedor y centro de costo, y alterna dos códigos de ítem según la paridad del índice (`i % 2 == 0`).
- `SiigoInvoiceGraphicRenderer` tiene fijos el teléfono del cliente, la ciudad y el vendedor.
- `columnas JSONB` `factura.datos_fiscales_snapshot` y `item_facturable.atributos_extra` existen en la base pero **no están mapeadas** en las entidades.

### Tarea propuesta

Poblar estos campos desde `CorporateClient` y `SubscriptionSite`, que ya tienen `municipality`, `phone`, `sellerCode`, `costCenter` y `cityCode`.

---

## 11. Higiene arrastrada de sprints anteriores

- **Configuración duplicada de la carpeta compartida.** `application.yml` define `siigo.carpeta-compartida.path` (línea 33, la que **sí** consume `LocalSiigoSharedFolderReader`) y `storage.siigo-pdf-path` (línea 113, perfil `docker`, apuntando a otro directorio). Hay que unificarlas o documentar cuál es la real; hoy es una trampa de despliegue.
- **H2 sigue declarado** como `testImplementation` en `build.gradle`. El Sprint 9 §8 pidió retirar el perfil `local` con H2 porque no reproduce índices únicos parciales, `JSONB`, enums nativos ni `gen_random_uuid()`. Si ya no se usa, retirar la dependencia.
- **`actualizado_en` no se actualiza.** `factura` tiene la columna con `DEFAULT now()` pero **no hay trigger** de `UPDATE`, y en la entidad está mapeada como `insertable = false, updatable = false`. Nunca refleja la última modificación.
- **`renewToken()` es un stub.** En `HttpOrderSystemClient` concatena `"-renewed-" + millis` al token configurado. La lógica de reintento ante 401 (HU-36 / RF-34) está bien construida, pero la renovación real no está implementada.
- **`ImportedFile.markError(reason)` descarta el motivo**: no lo persiste.
- **Numeración de HU.** El Sprint 9 §8 pidió corregir el corrimiento respecto al documento de requerimientos (la clasificación de PDFs quedó como HU-24, que originalmente era el total por NIC). Sigue pendiente. Las HU de S10 y S11 (HU-61 a HU-72) continúan la numeración de facto, no la del documento original.

---

## 12. Compuerta de cierre propuesta

- [ ] Un archivo ya ingerido no se reprocesa en el ciclo siguiente.
- [ ] Todo PDF que llega a la carpeta queda registrado, emparejado o no.
- [ ] El consecutivo de factura proviene de una secuencia y la base impide duplicados.
- [ ] El job tolera dos instancias simultáneas sin duplicar trabajo.
- [ ] Un plano de confirmación solo se aplica al lote al que pertenece.
- [ ] Anular una factura libera sus cuotas de instalación.
- [ ] Dos notas crédito parciales no pueden superar el total de la factura.
- [ ] Facturar contra un periodo cerrado tiene una regla explícita.
- [ ] No quedan periodos ni datos de cliente hardcodeados en los DTO ni en el renderer.
- [ ] Una sola propiedad define la carpeta compartida de Siigo.

---

## 13. Historias de usuario propuestas

| ID | Historia | Criterios clave |
|---|---|---|
| **HU-73** | Como analista, quiero que el sistema no vuelva a procesar archivos ya ingeridos, para que el ciclo horario sea predecible. | Control por hash; los procesados no se reabren; la carpeta no crece sin control. |
| **HU-74** | Como analista, quiero saber qué PDFs llegaron y no se pudieron emparejar, para reclamarlos a contabilidad. | Registro persistente de no emparejados, consultable por endpoint. |
| **HU-75** | Como contabilidad, quiero que el consecutivo de factura no pueda repetirse, para no romper la numeración fiscal. | Secuencia de base de datos e índice único; verificado bajo concurrencia. |
| **HU-76** | Como administrador, quiero que el ciclo de sincronización sea seguro con varias instancias, para poder escalar el servicio. | Lock distribuido; ejecuciones omitidas no se reportan como fallidas. |
| **HU-77** | Como analista, quiero que anular una factura libere sus cuotas de instalación, para no cobrarlas dos veces. | El contador de cuotas vuelve atrás al anular, igual que al regenerar. |
| **HU-78** | Como analista, quiero que dos notas crédito no superen el valor de la factura, para no generar saldos imposibles. | La validación acumula las notas aplicadas previamente. |
| **HU-79** | Como sistema, quiero recibir la tarifa anterior en las novedades de cambio de plan, para prorratear bien incluso en la primera factura del mes. | `novedad_orden.valor_anterior` poblada por la sincronización; el cálculo la usa como tarifa de apertura. |
| **HU-80** | Como analista, quiero que la factura muestre los datos reales del cliente y no valores de ejemplo, para poder enviarla sin corregir a mano. | Ciudad, teléfono, vendedor y centro de costo provienen de `cliente_corporativo` y `sede_suscripcion`. |
