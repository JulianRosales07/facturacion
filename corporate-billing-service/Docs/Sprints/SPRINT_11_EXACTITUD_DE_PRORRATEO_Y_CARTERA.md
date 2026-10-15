# Sprint 11 — Exactitud del prorrateo por novedades y corrección de cartera

- **Servicio:** `corporate-billing-service`
- **Paquete base:** `com.sistemaspalacios.billing`
- **Prerrequisito:** S10 completado (la tarifa de apertura se reconstruye a partir de la versión anterior de la factura).
- **Duración estimada:** 1 semana.
- **Estado:** **COMPLETADO**
- **Motivo:** hasta S10 el **tipo** de novedad no se consultaba en ningún punto del cálculo. `BillingCalculator` no tenía un solo `switch` sobre `OrderUpdateType`: ALTA, BAJA, CAMBIO_PLAN, SUSPENSION y REACTIVACION se procesaban de forma idéntica y lo único que hacía una novedad era desplazar la fecha de inicio del prorrateo. El resultado es que **una BAJA facturaba como si fuera un alta**, y un cambio de plan a mitad de mes cobraba el mes entero a la tarifa nueva.

---

## 1. Objetivo

1. Darle a cada tipo de novedad su **efecto económico real** sobre el consolidado del periodo.
2. Recuperar la **tarifa vigente al inicio del periodo**, que la sincronización de órdenes destruía, para poder partir el mes correctamente en un cambio de plan.
3. Dejar de perder los días consumidos por una sede **dada de baja dentro del periodo**.
4. Corregir dos defectos de cartera detectados al ejecutar la suite completa contra PostgreSQL.

> **Regla de oro del sprint:** al cliente se le cobra **lo que consumió, a la tarifa que tenía cuando lo consumió**. Ni un peso más ni un peso menos.

---

## 2. Cambio 1 — Semántica económica por tipo de novedad

### Comportamiento implementado

`BillingCalculator` recorre la línea de tiempo del periodo y emite un ítem por cada tramo facturable:

| Tipo de novedad | Efecto |
|---|---|
| `ALTA`, `REACTIVACION` | Abre el cobro desde la fecha efectiva. |
| `BAJA`, `SUSPENSION` | **Cierra** el cobro el día anterior a la fecha efectiva: solo se cobra lo realmente consumido. |
| `CAMBIO_PLAN`, `CAMBIO_SERVICIO` | Cierra el tramo a la tarifa anterior y abre uno nuevo a la tarifa entrante. |
| `TRASLADO_DIRECCION` | Sin efecto económico; no parte el tramo. |

**Ejemplo de referencia** (sede de $500.000/mes, periodo 2026-09 de 30 días, novedad efectiva el 16, mes comercial base 30):

| Novedad | Antes de S11 | Después de S11 |
|---|---|---|
| BAJA | 250.000 cobrando **del 16 al 30** | **250.000 cobrando del 1 al 15** |
| ALTA | 250.000 (del 16 al 30) | 250.000 (del 16 al 30), sin cambio |
| Sin novedad | 500.000 | 500.000, sin cambio |

### Tareas ejecutadas

- **`ProrationCalculator.calculateSegment(monthlyValue, segmentStart, segmentEnd, periodRange)`**: nuevo método con extremos inclusivos, recorte al periodo y atajo de mes comercial completo. El `calculate()` original queda intacto porque solo sabe cobrar *hacia adelante*, lo que sirve para altas pero no para bajas ni tramos intermedios.
- **Orden determinista de novedades** (`UPDATE_CHRONOLOGICAL_ORDER`): fecha efectiva, luego fecha de creación, luego id. Antes se tomaba `findFirst()` sobre un *stream* sin orden definido, así que con varias novedades en la misma sede **el resultado dependía del orden de la base de datos**.
- El recorrido acumula **tramos** (`RateSegment`) y solo después los materializa en ítems. Esto permite `mergeContiguousEqualRateSegments`: tramos contiguos a la misma tarifa se recomponen en una sola línea, para no partir la factura en una fecha que no significa nada para el cliente.
- Se preserva la regla **HU-10** de renuncia al prorrateo por observación comercial y el camino sin novedades, que mantiene el comportamiento anterior byte por byte (`BillingCalculatorTest` pasa sin modificaciones).

---

## 3. Cambio 2 — Tarifa mensual base: el cambio de plan a mitad de mes

**El problema, con números.** Sede de $500.000/mes que sube a $800.000 el 16 de septiembre. Lo correcto es 250.000 (15 días al plan viejo) + 400.000 (15 días al nuevo) = **650.000**. Lo que salía era **800.000**.

La causa: `item_facturable.valor_unitario` guarda el importe **ya prorrateado** (250.000), no la tarifa de la que sale (500.000). Y `OrderServiceImpl.upsertSite` ya había actualizado `sede_suscripcion.valor_mensual` a 800.000. Cuando el calculador preguntaba "¿a cuánto venía esta sede el día 1?", la única fuente disponible respondía 800.000. **La tarifa anterior no existía en ninguna parte del sistema.**

**El error cambia de signo según la dirección del cambio**, y eso es lo que lo hacía peligroso:

| Cambio el día 16 | Correcto | Antes | Desvío |
|---|---|---|---|
| Sube $500.000 → $800.000 | 650.000 | 800.000 | **+150.000** el cliente paga de más y reclama |
| Baja $500.000 → $300.000 | 400.000 | 300.000 | **−100.000** la empresa cobra de menos y nadie reclama |

El caso de bajada es el costoso: es fuga de ingresos silenciosa. La factura se ve coherente, cuadra con sus ítems y cuadra con el asiento contable. Simplemente cobra menos de lo pactado.

### Tareas ejecutadas

- Migración **`V10__tarifa_base_item_facturable.sql`**: columna `item_facturable.valor_mensual_base` (`NUMERIC(14,2)`, **nullable** a propósito) mapeada a `InvoiceItem.baseMonthlyRate`. Incluye un *backfill* conservador: si la descripción de la línea indica que cubrió el mes completo (`GR 01-<último día>`), el valor unitario **ya es** la tarifa mensual; las líneas prorrateadas se dejan en `NULL` porque el factor no se persistió y la tarifa no es reconstruible sin ambigüedad.
- **`BillingServiceImpl.resolveOpeningRates(clientId, period, previousVersion)`** con tres niveles de precedencia:
  1. La **versión anterior de la factura del mismo periodo** (caso de regeneración). Es el dato más fiable: es lo que efectivamente se facturó.
  2. La **factura vigente del periodo inmediatamente anterior**. Cubre el caso en que el cambio de plan llega **antes** de la primera emisión del mes, que de otro modo quedaba sin solución.
  3. Vacío: el calculador cae a `sede.valor_mensual`, el comportamiento anterior.
- `extractRatesBySite` toma el **primer** tramo de cada sede: si el mes ya venía partido, el primero es el que estaba vigente el día 1. `InvoiceItem.isInstallationFeeLine()` excluye las cuotas de instalación del cálculo de tarifas.
- Sobrecarga `BillingCalculator.calculateConsolidatedInvoice(..., ratesAtPeriodStart)`. La firma de 6 argumentos se conserva delegando con mapa vacío.

### Convención de tarifas documentada

- **Tarifa de apertura**: la de `resolveOpeningRates`. Nunca `sede.valor_mensual` si hay algo mejor.
- **Tarifa entrante**: `novedad.valor` si viene informada; si no, `sede.valor_mensual`.

---

## 4. Cambio 3 — Sedes dadas de baja dentro del periodo

**Problema:** `BillingServiceImpl` cargaba las sedes con `findByClientIdAndStatus(..., ACTIVO)` y el calculador descartaba las inactivas con un `if (!site.isActive()) continue;`. Cuando la sincronización de órdenes procesaba una BAJA y desactivaba la sede, **la sede desaparecía por completo de la factura** y los días que el cliente sí consumió antes de la baja no se cobraban nunca.

Nótese que esto convertía el Cambio 1 en papel mojado: no sirve calcular bien el tramo del 1 al 15 si la sede nunca llega al calculador.

### Tareas ejecutadas

- **`BillingServiceImpl.loadBillableSites(clientId, period, periodUpdates)`**: a las sedes activas suma las **inactivas que tengan una novedad de BAJA o SUSPENSION con fecha efectiva dentro del periodo**. Esa fecha es la que define hasta cuándo cobrar.
- **`BillingCalculator.isBillableInPeriod(site, siteUpdates, periodRange)`** aplica el mismo criterio en el dominio, de modo que la regla no dependa solo de cómo se cargaron los datos.
- Una sede dada de baja en **periodos anteriores** no tiene novedades en este periodo y se sigue omitiendo. No se reactivan cobros viejos.

> Se resolvió por la **novedad** y no por una fecha de desactivación en `sede_suscripcion` porque esa columna no existe: la novedad es la única fuente que dice *cuándo* se dio de baja.

---

## 5. Cambio 4 — Dos defectos de cartera (preexistentes)

Ambos salieron a la luz al ejecutar la suite completa contra PostgreSQL y **no provienen del trabajo de versionado**.

### 4.1 `paidAmount` nunca se calculaba — `PaymentServiceImpl`

Dos bugs encadenados en el mismo flujo:

1. **Valor descartado.** `enrichInvoiceBalances` hacía `.forEach(c -> totalPaid.plus(c.getAppliedAmount()))`. `Money` es **inmutable**: `plus()` devuelve una instancia nueva y ese `forEach` la tiraba. El acumulado se quedaba en cero siempre. Se verificó por búsqueda en todo `src/main/java` que era la única instancia del patrón.
2. **Orden de operaciones.** `register()` invoca `enrichInvoiceBalances` **antes** de que el pago exista, así que por definición solo puede ver pagos anteriores. Después se crea el cruce `PaymentInvoice` y la factura pasa a `PAGADA`, pero nadie sumaba ese cruce a `paidAmount`.

**Impacto en producción:** con `paidAmount` en cero, `getPendingBalance()` nunca restaba los pagos. Toda factura pagada **seguía apareciendo con saldo pendiente completo** en el listado de cartera, el filtro `soloConSaldo` (HU-58) no filtraba nada y el aviso de pago parcial (HU-53) calculaba mal la deuda restante. La factura quedaba en un estado contradictorio consigo misma: `PAGADA` con saldo igual al total.

**Corrección:** bucle que reasigna el acumulado, y suma del cruce recién aplicado en el mismo bucle donde se marca la factura como pagada. `paidAmount` sigue siendo `@Transient` a propósito —es un valor derivado de `pago_factura`—; lo que estaba mal era la reconstrucción en memoria, no los datos persistidos.

### 4.2 Severidad del ciclo de sincronización — `ScheduledSiigoSyncJob`

El job acumulaba cualquier error y siempre terminaba en `CON_ADVERTENCIAS`, incluso cuando una fase completa se caía. Ahora se distinguen dos severidades:

- **Fallo de ítem** (un lote concreto que no se pudo confirmar): el ciclo hizo su trabajo con el resto → `CON_ADVERTENCIAS`.
- **Fallo de fase** (la carpeta compartida es inaccesible y no se pudo clasificar ningún PDF): no hubo forma de procesar nada de esa fase → `FALLIDA`, para que el monitoreo lo trate como incidente.

Se añadió `SyncStatusEntity.fail(confirmedBatches, detectedInvoices, classifiedPdfs, errorMessage)` porque el `fail()` original **descartaba los contadores**: si se confirmaban 3 lotes y luego fallaba la fase de PDF, la auditoría reportaba 0 lotes confirmados. El estado `CON_ADVERTENCIAS` quedó documentado en la entidad; no figuraba en el comentario de `V7`.

---

## 6. Pruebas

**`BillingCalculatorNoveltyTest`** (14 casos, todos sobre el escenario de referencia de $500.000 y novedad el día 16):

- Referencia sin novedades: mes completo.
- BAJA y SUSPENSION cobran `GR 01-15`, no `16-30`.
- Sede **ya desactivada** por la baja sigue cobrando su tramo consumido.
- Sede inactiva **sin** novedades en el periodo se sigue omitiendo.
- ALTA prorratea hacia adelante (`GR 16-30`).
- TRASLADO_DIRECCION no altera el valor.
- **Subida de plan** 500k → 800k: dos ítems de 250.000 y 400.000, total 650.000 con IVA 773.500.
- **Bajada de plan** 500k → 300k: dos ítems de 250.000 y 150.000, total 400.000 con IVA 476.000.
- Sin tarifa de apertura conocida, los tramos se fusionan en un **único** ítem de mes completo.
- CAMBIO_SERVICIO parte el mes igual que el cambio de plan.
- Alta y baja en el mismo mes: solo el tramo intermedio (`GR 06-20`).
- La renuncia al prorrateo (HU-10) sigue ganando sobre la novedad.
- Novedad con fecha posterior al cierre del periodo no altera el consolidado.

**Regresiones en verde:** `BillingCalculatorTest`, `ProrationCalculatorTest`, `PaymentServiceIntegrationTest`, `ScheduledSiigoSyncJobTest`, `ArchitectureLayerRulesTest`.

> **Defecto encontrado por estas pruebas.** La primera versión de la fusión de tramos devolvía únicamente el primer ítem y **perdía la mitad del mes** (facturaba 400.000 en lugar de 800.000). Se corrigió separando el cálculo de tramos de su materialización. Queda anotado porque es exactamente la clase de error que un test de valores concretos atrapa y una revisión de código no.

---

## 7. Compuerta de cierre del Sprint 11

- [x] Cada tipo de novedad tiene efecto económico propio; una BAJA cobra lo consumido y no lo no consumido.
- [x] Un cambio de plan a mitad de mes se cobra con **las dos tarifas**, tanto al subir como al bajar.
- [x] La tarifa vigente al inicio del periodo es recuperable desde la versión anterior o desde el mes previo.
- [x] Una sede dada de baja dentro del periodo sigue facturando su tramo consumido.
- [x] Varias novedades en una misma sede producen un resultado determinista.
- [x] `paidAmount` refleja los pagos aplicados; una factura pagada muestra saldo cero.
- [x] Un fallo de fase en la sincronización se registra como `FALLIDA` conservando los contadores del ciclo.
- [x] Suite completa: **130 tests, BUILD SUCCESSFUL** con migraciones `V1`→`V10` sobre PostgreSQL 16.

---

## 8. Limitación conocida (no es defecto)

Si un `CAMBIO_PLAN` llega **antes de la primera emisión del mes** y el cliente **tampoco tiene factura del mes anterior**, no existe ninguna fuente para la tarifa de apertura y se cobra el mes completo a la tarifa nueva. Es el único caso que no se puede resolver sin que el sistema de órdenes envíe la tarifa anterior en la novedad.

Está cubierto por el test *"Sin tarifa de apertura conocida, el cambio de plan degrada a un ÚNICO ítem de mes completo"*, de modo que el comportamiento sea consciente y no una sorpresa. Ver Sprint 12 para la vía de cierre definitiva.

---

## 9. Historias de usuario del sprint

| ID | Historia | Criterios clave |
|---|---|---|
| **HU-67** | Como analista, quiero que una baja a mitad de mes cobre solo los días consumidos, para no facturar servicio que el cliente no tuvo. | BAJA y SUSPENSION cierran el cobro el día anterior a la fecha efectiva; el rango de la descripción lo refleja. |
| **HU-68** | Como analista, quiero que un cambio de plan parta el mes con la tarifa vieja y la nueva, para no cobrar de más ni de menos. | Dos ítems con su tarifa base; verificado en subida y en bajada de plan. |
| **HU-69** | Como contabilidad, quiero que la tarifa mensual de cada línea quede registrada, para poder reconstruir cualquier cobro. | `item_facturable.valor_mensual_base` poblada en cada emisión; *backfill* conservador de las líneas de mes completo. |
| **HU-70** | Como analista, quiero que una sede dada de baja siga facturando lo que consumió, para no perder ingresos al desactivarla. | Las sedes inactivas con BAJA o SUSPENSION en el periodo entran al consolidado; las de baja antigua no. |
| **HU-71** | Como analista de cartera, quiero que una factura pagada muestre saldo cero, para no cobrar dos veces. | `paidAmount` acumula los cruces aplicados; `soloConSaldo` excluye las pagadas; el aviso de pago parcial calcula la deuda real. |
| **HU-72** | Como administrador, quiero distinguir un ciclo de sincronización con advertencias de uno fallido, para priorizar incidentes. | Fallo de ítem → `CON_ADVERTENCIAS`; fallo de fase → `FALLIDA` conservando los contadores del ciclo. |
