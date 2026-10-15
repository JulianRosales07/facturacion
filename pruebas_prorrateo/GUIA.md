# Prueba de novedades sobre la factura en preparación

Necesitas el backend y el frontend corriendo, y un usuario con los permisos `IMPORTS_EXECUTE` e `INVOICES_CREATE`.

**Qué se comprueba:** cada novedad importada se incorpora a la **misma factura** (mismo número), sin crear otra factura ni otra versión. El plan del mes queda en *Cargos fijos* y cada novedad en *Otros conceptos* con su incremento.

## Pasos (iguales para todos los escenarios)

1. **Importación de órdenes** → en *Periodo de importación* elige el periodo del escenario → sube el archivo `…1_…_base.xlsx`.
2. **Facturas recibidas** → *Generar factura* → NIT y periodo → *Generar y ver detalle*. La factura sale con el mes completo al plan anterior. **Anota su número.**
3. Vuelve a **Importación de órdenes** (mismo periodo) → sube el archivo con la novedad. Debe aparecer el aviso *"Factura FESP … actualizada con las novedades: total … → …"*.
4. **Facturas recibidas** → filtro **Origen: Internas (en preparación)** → busca el NIT → **Ver conceptos**. Debe tener **el mismo número**, una sola factura, y la sección *Conoce los cobros a detalle* con los bloques de la tabla. El botón *Descargar PDF* muestra los mismos bloques.

Ya no hace falta *Regenerar*.

## Escenario A: octubre 2026 (mes de 31 días), dos novedades sobre la misma factura

Periodo `2026-10` · NIT `901555777`.

| Paso | Archivo | Conceptos esperados | Subtotal / IVA / Total |
|---|---|---|---|
| 1–2 | `A1_octubre_base.xlsx` | `GR 01-31 OCT` 500.000 | 500.000 / 95.000 / 595.000 |
| 3 | `A2_octubre_cambio_plan_dia16.xlsx` (cambio a 800.000 el 16 + sede norte con alta el 31) | Cargos fijos: plan 500.000 (01-31). Otros conceptos: cambio de plan a 800.000 desde el 16 → 150.000. La sede norte no aparece | 650.000 / 123.500 / 773.500 |
| 4 | `A3_octubre_segundo_cambio_dia21.xlsx` (cambio a 1.100.000 el 21) | Cargos fijos: 500.000. Otros conceptos: 150.000 (desde el 16) + 100.000 (desde el 21) | 750.000 / 142.500 / 892.500 |
| 5 | Vuelve a subir `A3…` | Sin cambios: mismos conceptos, sin duplicar | 892.500 |

## Escenario B: febrero 2027 (mes de 28 días)

Periodo `2027-02` · NIT `901555888` · archivos `B1_febrero_base.xlsx` y `B2_febrero_cambio_plan_dia15.xlsx`.

| Conceptos tras la novedad | Subtotal / IVA / Total |
|---|---|
| Cargos fijos 500.000 · Otros conceptos: cambio de plan desde el 15 → 160.000 (16 días comerciales) | 660.000 / 125.400 / 785.400 |

## Escenario C: septiembre 2026 (redondeo)

Periodo `2026-09` · NIT `901555999` · archivos `C1_septiembre_base.xlsx` y `C2_septiembre_cambio_plan_dia15.xlsx`.

| Conceptos tras la novedad | Subtotal / IVA / Total |
|---|---|
| Cargos fijos 12.000.000 · Otros conceptos: cambio de plan desde el 15 → 1.600.000 | 13.600.000 / 2.584.000 / 16.184.000 |

## Avisos que puedes ver al importar

| Aviso | Significa |
|---|---|
| *Factura … actualizada con las novedades* | Se incorporaron a la factura en preparación. |
| *El cliente … aún no tiene factura del periodo* | Importaste la novedad antes del paso 2. Las novedades quedan guardadas y entran al generar la factura. |
| *Factura … no se modificó: … pasan como ajuste a la factura …* | La factura ya estaba emitida o pagada (o el mes se facturó en Siigo). No se toca: el impacto de la novedad entra como ajuste en la siguiente factura en preparación del cliente. |

## Cuidados

- **El orden no importa.** Si importas la novedad antes de generar la factura, la novedad guarda la tarifa que tenía la sede y el resultado es el mismo.
- **La pasarela cobra de una vez** la factura interna con sus novedades, junto con las de Siigo pendientes. Desde que el cliente inicia el pago, la factura ya no se recalcula.
- Si al importar ves clientes "· Demo", el backend no respondió.
- Una **bajada de plan** no aparece como línea negativa: se descuenta del cargo fijo y se muestra en Otros conceptos como "Descuento por bajada de plan".
- **Deuda anterior** suma las facturas oficiales de Siigo del cliente emitidas antes del periodo; para los NIT de prueba sale en 0.
- La descripción de las líneas muestra el nombre más reciente de la sede (por ejemplo 300MB), porque la importación actualiza el nombre. Los valores son los correctos.

## Ver qué pasó con cada novedad

- En **Importación de órdenes**, la sección **Novedades por cliente** muestra el NIT y el periodo elegidos. Si el archivo importado traía un solo NIT, se carga sola.
- En el **detalle de cada factura** está la misma sección, **Novedades del periodo**.
- Por cada novedad muestra: sede y orden, tipo, fecha efectiva, valor anterior y nuevo, impacto, estado (*Pendiente de facturar*, *Aplicada*, *Ajuste pendiente*, *Ajuste aplicado* o *En revisión*) y un enlace a la factura donde se cobró.
- Las novedades **en revisión** (impacto no calculable, por ejemplo con "no cobrar prorrateo") aparecen resaltadas y no se cobran solas.
- **Reprocesar novedades** (permiso `SYNC_EXECUTE`) vuelve a aplicar las novedades del cliente y periodo. Repetirlo no duplica nada.
