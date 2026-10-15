# Sprint 13 — Ingesta del corte de cartera de Siigo como fuente de verdad

- **Servicio:** `corporate-billing-service`
- **Paquete base:** `com.sistemaspalacios.billing`
- **Prerrequisito:** S10 y S11 completados.
- **Duración estimada:** 1,5 semanas.
- **Estado:** **COMPLETADO**
- **Motivo:** al revisar el contenido real de la carpeta compartida se encontró que Siigo deja **dos** documentos: el PDF de la factura y un **Excel de saldos de cartera**. El microservicio leía el PDF pero **ignoraba el Excel por completo**, por tres razones acumuladas. Decisión de negocio tomada en este sprint: la columna SALDO de ese archivo es la **fuente de verdad** del saldo pendiente.

---

## 1. Por qué el Excel se ignoraba

Tres filtros encadenados, todos verificados en código antes de tocar nada:

1. **Extensión.** `LocalSiigoSharedFolderReader.listConfirmationFlatFiles()` aceptaba solo `.txt`, `.csv`, `.dat` y `.json`.
2. **Nombre.** El mismo filtro exigía que el nombre contuviera `consecutivo`, `confirmacion`, `siigo` o `lote`. `saldos_de_cartera.xlsx` no contiene ninguno.
3. **Formato.** No había librería de Excel en `build.gradle`. Un `.xlsx` es un ZIP binario y `SiigoConfirmationParser` solo interpreta JSON o texto delimitado, así que aunque hubiera pasado los filtros no podía abrirse.

Resultado: el archivo entraba a la carpeta y nadie lo miraba. Ni un log.

---

## 2. Estructura real del archivo

Verificada sobre el ejemplo de **1.048 filas de datos** (`Docs/Ejemplo carpeta siggo/saldos_de_cartera.xlsx`): hoja única `Hoja1`, título en las filas 1–2, **encabezados en la fila 5**, datos desde la **fila 6**.

| Col | Encabezado | Contenido | Uso |
|:---:|---|---|---|
| A | IDENTIFICACIÓN | `1088970617` | NIT del cliente |
| B | SUCURSAL | `0` | — |
| C | CUENTA | `1305051001`…`1305051007` | Cuenta contable de cartera |
| D | TIPO DE COMPROBANTE | `F` | Factura |
| E | CÓDIGO COMPROBANTE | **`1` o `20`** | Mapea a `BillingType.F1` / `F20` |
| F | NÚMERO DE DOCUMENTO | `51993` | Consecutivo de la factura |
| H | FECHA VENCIMIENTO | `20260925` | Entero `yyyyMMdd` |
| L | SALDO | `5694150` | **Saldo pendiente — fuente de verdad** |
| Q | FECHA DOCUMENTO | `20260910` | Entero `yyyyMMdd` |

**El cruce con el PDF es determinista.** El nombre `F001000000519930000.PDF` se descompone con el regex que ya existía en `SiigoPdfImporterService`: `typeCode=001` → F1, `consecutivo=51993`. La fila 1023 del Excel trae exactamente ese par (`E=1`, `F=51993`) y aporta el NIT `1088970617`. Esa es la pieza que le faltaba al emparejamiento: antes se adivinaba el cliente leyendo el texto del PDF con PDFBox y cayendo a un *fallback* por NIT.

---

## 3. Consecuencia de la decisión: qué cambia en el dominio

`Invoice.getPendingBalance()` cambia de significado:

```java
public Money getPendingBalance() {
    if (siigoBalance != null) return siigoBalance;   // fuente de verdad
    return getComputedPendingBalance();              // proyección local
}
```

- **`saldo_siigo`** es el saldo autoritativo. `null` significa que nunca llegó un corte para esa factura, y entonces rige el cálculo local.
- **`getComputedPendingBalance()`** conserva la fórmula anterior (`valor_total − notas_credito − pagos`). Deja de ser la verdad y pasa a ser la única forma de **detectar que Siigo y el microservicio no coinciden**.
- **`getBalanceDiscrepancy()`** expone la diferencia. Positiva: Siigo reporta más saldo del que el sistema conoce. Negativa: Siigo reporta menos, típicamente un pago registrado directamente en Siigo.

**Transición de estado.** Si Siigo dice saldo cero, la factura pasa a `PAGADA` aunque el pago nunca se haya registrado en el microservicio. Es la consecuencia directa de la decisión y queda auditada en la línea del corte. No se toca una factura anulada ni una versión reemplazada.

---

## 4. Los dos riesgos que la decisión no resuelve por sí sola

La decisión dice *qué* manda, no *cómo* aplicarlo. Estos dos puntos se resolvieron explícitamente porque, mal manejados, el saldo autoritativo destruye datos.

### 4.1 Cortes obsoletos

El archivo es una **foto tomada en un instante**. Si el microservicio registra un pago a las 10:00 y alguien copia un corte generado a las 09:00, aplicarlo revertiría el pago.

**Solución:** cada factura guarda `saldo_siigo_corte_en`, la fecha de **generación** del corte (no de su ingesta). `Invoice.applySiigoBalance(...)` rechaza cualquier corte anterior al ya aplicado y la fila queda marcada `CORTE_OBSOLETO`. La fecha de generación se deriva de la **fecha de documento más reciente del propio archivo**, para que copiar un archivo viejo hoy no lo haga parecer nuevo.

### 4.2 Ausencia del archivo

Que una factura **no aparezca** en el corte podría significar que está pagada… o que el extracto es parcial. Asumir lo primero marcaría cientos de facturas como pagadas de golpe.

**Solución:** por defecto **no** se asume saldo cero. Las ausencias se cuentan en `cartera_snapshot.facturas_sin_corte` y se reportan. La política se invierte con:

```yaml
siigo:
  cartera:
    tratar-ausencia-como-saldo-cero: ${SIIGO_CARTERA_AUSENCIA_SALDO_CERO:false}
```

> **Pendiente de confirmar con contabilidad:** ¿el archivo trae siempre la cartera completa? Si la respuesta es sí, activar la bandera. Mientras no se confirme, el valor conservador evita un daño masivo e irreversible.

---

## 5. Componentes implementados

### Migración `V11__cartera_siigo_fuente_de_verdad.sql`

- `factura`: `saldo_siigo`, `saldo_siigo_actualizado_en`, `saldo_siigo_corte_en`, `saldo_siigo_snapshot_id`.
- **`cartera_snapshot`**: cabecera de cada archivo ingerido con hash SHA-256 (índice único), fecha de corte, contadores y estado.
- **`cartera_snapshot_linea`**: **todas** las filas del archivo, incluidas las que no emparejaron. Guarda además `saldo_calculado` y `diferencia` congelados al aplicar, de modo que el reporte de conciliación sea una lectura pura: los valores de los que depende el cálculo local son campos derivados que no se pueden reconstruir en SQL.

### Puerto y adaptador

- `service/siigo/SiigoCarteraReader` — puerto con los *records* `CarteraRow`, `CarteraRowError` y `CarteraSnapshotData`.
- `infrastructure/siigo/PoiXlsxCarteraReader` — adaptador Apache POI **5.4.1**. Decisiones de lectura:
  - **Los encabezados se localizan por contenido**, buscando `IDENTIFICACIÓN` con normalización de tildes, no por índice fijo. Si contabilidad agrega una fila de título, el archivo sigue funcionando.
  - Las fechas llegan como **entero `yyyyMMdd`**; se soporta además la fecha nativa de Excel por si el formato cambia.
  - **El saldo es un `double` en el xlsx y arrastra ruido binario** (`1122566.6599999999` aparece literalmente en el archivo real). Se pasa por `String` antes de `BigDecimal` para no amplificarlo y se redondea `HALF_UP` a 2 decimales, conforme a la norma del proyecto sobre importes.
  - Un error de formato en una fila **no aborta** la lectura: se reporta y se continúa.
  - Se excluyen los temporales `~$*.xlsx` que Excel crea cuando alguien tiene el libro abierto.

### Lector de la carpeta compartida

- Nuevo método `listCarteraSpreadsheets()` en el puerto `SiigoSharedFolderReader`, separado de los planos de confirmación porque son documentos distintos.
- **Se eliminó el filtro por palabra clave en el nombre** de `listConfirmationFlatFiles()`. Era frágil: bastaba con que contabilidad renombrara el archivo para que dejara de procesarse en silencio. Ahora filtra solo por extensión y el parser decide si el contenido le sirve.

### `CarteraSyncService`

- **Idempotencia por hash**: un archivo ya ingerido se omite sin reabrirse, necesario porque el lector no mueve ni marca lo procesado.
- **Emparejamiento determinista** por `(BillingType derivado de la columna E, consecutivo normalizado de la columna F)`. La normalización a dígitos hace que `"FESP 51993"` y `"51993"` produzcan la misma clave. El índice se construye una vez por archivo, no por fila.
- **Validación cruzada del NIT** contra el snapshot fiscal de la factura. No bloquea la aplicación, pero marca la fila `NIT_DISCREPANTE` y la deja fuera para revisión manual.
- Lecturas puras `getLatestReconciliation()` y `getLatestUnresolvedRows()`.

### Superficie REST

| Método | Ruta | Roles | Nota |
|---|---|---|---|
| `POST` | `/api/v1/cartera/sincronizar` | ANALISTA, ADMINISTRADOR, SISTEMA | Fuerza la ingesta sin esperar el ciclo horario |
| `GET` | `/api/v1/cartera/conciliacion` | ANALISTA, ADMINISTRADOR | **Lectura pura.** Diferencias del último corte |
| `GET` | `/api/v1/cartera/pendientes-de-resolver` | ANALISTA, ADMINISTRADOR | Filas sin aplicar, con el motivo |

`FacturaResumenDto` incorpora `saldoSiigo` y `saldoCalculado`, de modo que el portal muestre ambos y la diferencia sea visible.

### Job horario

Tercera fase en `ScheduledSiigoSyncJob`: confirmar lotes → clasificar PDFs → **ingerir corte de cartera**. Un fallo de esta fase se clasifica como `FALLIDA` según la severidad introducida en S11.

---

## 6. Defecto propio corregido durante el sprint

La primera versión de `GET /cartera/conciliacion` **ejecutaba la ingesta** para calcular las diferencias. Un `GET` que muta estado es incorrecto y además habría reprocesado archivos en cada consulta del portal. Se corrigió congelando `saldo_calculado` y `diferencia` en cada fila al aplicarla, con lo que la conciliación quedó como lectura sobre datos persistidos.

---

## 7. Pruebas

**`PoiXlsxCarteraReaderTest`** (9 casos) construye libros con la estructura real:

- Lee la fila real del corte (NIT, tipo de facturación, consecutivo `51993`, saldo `5.694.150`, vencimiento y fecha de documento).
- El código de comprobante `20` mapea a F20 y cualquier otro a F1.
- El ruido binario `1122566.6599999999` se redondea a `1122566.66` con escala 2.
- La fecha de corte es la fecha de documento más reciente del archivo.
- Una fila con error de formato no aborta la lectura del resto y se reporta con su número de fila real de Excel.
- Los encabezados se localizan aunque estén desplazados a la fila 8.
- Un archivo inexistente devuelve vacío sin excepción.
- El NIT `900.163.149-2` se normaliza a `900163149`.
- Las filas totalmente vacías se ignoran sin contarse como error.

**Resultado de la corrida de cierre:** `BUILD SUCCESSFUL`, **0 fallos** en toda la suite, con las migraciones `V1`→`V11` aplicadas contra PostgreSQL 16 vía Testcontainers.

---

## 8. Compuerta de cierre del Sprint 13

- [x] El `.xlsx` de la carpeta compartida se detecta y se lee.
- [x] El emparejamiento factura↔fila es determinista por código de comprobante y consecutivo.
- [x] El PDF y el Excel se cruzan sobre la misma factura.
- [x] El saldo de Siigo manda sobre el calculado localmente.
- [x] Un corte anterior al ya aplicado no sobrescribe el saldo.
- [x] Una factura ausente del corte **no** se asume pagada sin activar la política explícita.
- [x] Toda fila queda auditada, emparejada o no, con su motivo.
- [x] Un archivo ya ingerido no se reprocesa.
- [x] Los importes se leen con `BigDecimal` y `HALF_UP`, nunca como `double`.
- [x] La conciliación es una lectura pura.

---

## 9. Historias de usuario del sprint

| ID | Historia | Criterios clave |
|---|---|---|
| **HU-81** | Como analista, quiero que el sistema lea el Excel de cartera que Siigo deja en la carpeta, para no transcribir saldos a mano. | Se detecta por extensión sin depender del nombre; encabezados localizados por contenido; errores por fila no abortan la lectura. |
| **HU-82** | Como analista, quiero que cada factura del corte se asocie a su cliente corporativo, para clasificar sin ambigüedad. | Emparejamiento por código de comprobante y consecutivo; NIT validado contra el snapshot fiscal de la factura. |
| **HU-83** | Como contabilidad, quiero que el saldo de Siigo sea el que manda, para que el portal muestre la cartera real. | `saldo_siigo` tiene precedencia; saldo cero pasa la factura a PAGADA; el saldo calculado se conserva para conciliar. |
| **HU-84** | Como administrador, quiero que un corte viejo no revierta la cartera de hoy, para no perder movimientos recientes. | Se compara la fecha de generación del corte; las filas obsoletas se rechazan y quedan marcadas. |
| **HU-85** | Como analista, quiero ver dónde Siigo y el sistema no coinciden, para investigar diferencias. | Reporte de conciliación como lectura pura, con diferencia por factura y diferencia neta. |
| **HU-86** | Como analista, quiero ver qué filas del corte no se pudieron aplicar, para reclamarlas. | Endpoint de pendientes con estado `SIN_FACTURA`, `NIT_DISCREPANTE`, `CORTE_OBSOLETO` o `ERROR_FORMATO`. |
| **HU-87** | Como administrador, quiero que un corte parcial no marque facturas como pagadas por error, para no perder cobros. | La ausencia del archivo no implica saldo cero salvo que se active la política; las ausencias se cuentan y reportan. |
