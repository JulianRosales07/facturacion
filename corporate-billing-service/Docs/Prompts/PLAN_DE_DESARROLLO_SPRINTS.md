# Plan de desarrollo por sprints — `corporate-billing-service`

Plan para construir el microservicio **por partes**, en orden, siguiendo las historias de usuario. La regla de oro: **ningún sprint se cierra ni se pasa al siguiente hasta que sus pruebas estén en verde y sus historias validadas** (ver *Compuerta de cierre*).

---

## Enfoque

- **Iterativo y vertical.** Primero los cimientos y el cálculo puro; luego un *slice* completo de punta a punta (generar factura) y después los complementos (ajustes, exportación).
- **Una fase en verde antes de la siguiente.** Cada sprint termina con una demo funcional y su suite de pruebas pasando.
- **Compuerta obligatoria entre sprints** (ver abajo). Si no pasa, no se avanza: se corrige en el mismo sprint.

---

## Resumen de sprints

| Sprint | Objetivo | Historias | Duración |
|---|---|---|---|
| **S0** | Scaffold y cimientos | (base técnica) | 1 semana |
| **S1** | Dominio + reglas de cálculo | HU-10 … HU-16, HU-18 | 2 semanas |
| **S2** | Persistencia | HU-08, HU-09, HU-29* | 2 semanas |
| **S3** | Integración con Órdenes | HU-01 … HU-05, HU-17, HU-25, HU-27 | 2 semanas |
| **S4** | Generar factura consolidada (*slice* canónico) | HU-06, HU-07, HU-26 | 2 semanas |
| **S5** | Ajustes: anulación y notas crédito | HU-19, HU-20 | 1.5 semanas |
| **S6** | Exportación a Siigo y confirmación | HU-21, HU-22, HU-23, HU-24 | 2 semanas |
| **S7** | Estabilización y no funcionales | RNF + pendientes | 1 semana |

*HU-29 corresponde al RF-29 (conservar histórico vía inactivación).*

**Duración total estimada: ~13.5 semanas (~3 meses).** Coincide con el estimado de la reunión de ~2 meses para dejar operativa la primera fase (Órdenes + generación de factura), que aquí son S0–S4.

---

## Compuerta de cierre (Definition of Done por sprint)

Un sprint solo se da por terminado —y habilita el siguiente— cuando **todo** lo siguiente se cumple:

1. Todas las **pruebas unitarias** del sprint en verde.
2. Todas las **pruebas de integración** (Testcontainers) del sprint en verde.
3. **ArchUnit** pasa (no se violan las reglas de capas).
4. Cada **historia de usuario** del sprint validada contra **sus criterios de aceptación** (revisión manual/demo).
5. **Code review** aprobado y sin deuda crítica pendiente.
6. Build reproducible (`./gradlew build` verde) y documentación/README actualizados.

Si algún punto falla, el sprint **no cierra**: se corrige antes de pasar al siguiente.

---

## Estrategia de pruebas

- **Unitarias de dominio** — reglas de cálculo (prorrateo, IVA, retenciones, cuotas, descuentos) y balance del asiento; sin Spring.
- **Integración (Testcontainers)** — repositorios, migraciones Flyway y el índice único de factura activa.
- **De contrato / cliente** — el cliente de Órdenes contra un servidor mock.
- **De aceptación por HU** — un caso por criterio de aceptación (Condición → Resultado).
- **ArchUnit** — orden de capas y no acceso directo `web → repository`.

---

## Detalle por sprint

### S0 — Scaffold y cimientos · 1 semana
**Objetivo:** dejar el proyecto listo para construir sobre él.
**Entregables:** proyecto Spring Initializr (Gradle Groovy, Java 21, Spring Boot 4.1.1); estructura de capas; Flyway `V1` corriendo; ArchUnit con la regla base de capas; Actuator/health; configuración por entornos.
**Pruebas:** el contexto de Spring levanta; `V1` aplica en Testcontainers; ArchUnit base pasa; `/actuator/health` responde.
**Compuerta:** build verde + health OK + migración e imports de capas validados por ArchUnit.

### S1 — Dominio + reglas de cálculo · 2 semanas
**Objetivo:** el corazón del microservicio como lógica pura y testeada.
**Historias:** HU-10 (fecha de cobro), HU-11 (prorrateo), HU-12 (cuotas), HU-13 (descuentos), HU-14 (IVA), HU-15 (retenciones), HU-16 (asiento balanceado), HU-18 (descripción de línea).
**Entregables:** entidades y value objects (`Dinero`, `PerfilTributario`, …) sin persistir todavía; servicios de dominio (`CalculadoraFacturacion`, `CalculadoraImpuestos`, `CalculadoraProrrateo`, `GeneradorAsientoContable`).
**Pruebas:** unitarias exhaustivas con casos de borde (novedad a mitad de periodo, cliente con/sin retención, cuota que se agota, observación que anula prorrateo, ítem no gravado, asiento que debe cuadrar débitos = créditos).
**Compuerta:** cada HU de cálculo validada por sus pruebas; cobertura alta del dominio.

### S2 — Persistencia · 2 semanas
**Objetivo:** persistir el dominio y garantizar las invariantes en BD.
**Historias:** HU-08 (una factura activa por periodo), HU-09 (snapshot fiscal inmutable), HU-29/RF-29 (conservar histórico por inactivación).
**Entregables:** entidades anotadas `@Entity` (DDD-Lite, sin mapper aparte), repositorios Spring Data, catálogos sembrados.
**Pruebas:** integración con Testcontainers; test del índice único parcial de factura activa; test de que el snapshot no cambia al editar el cliente; soft delete conserva histórico.
**Compuerta:** persistencia estable + invariantes verificadas contra Postgres real.

### S3 — Integración con Órdenes · 2 semanas
**Objetivo:** poblar los maestros desde el sistema de Órdenes.
**Historias:** HU-01 (sincronizar), HU-02 (no duplicar), HU-03 (archivo alternativa), HU-04 (ver novedades), HU-05 (origen/marca), HU-17 (mapeo de cuentas), HU-25 (catálogos y perfil), HU-27 (trazabilidad de importación).
**Entregables:** interfaz `ClienteSistemaOrdenes` + implementación HTTP (`RestClient`); `ServicioOrdenes.sincronizar`; importador de archivo; configuración de `mapeo_cuenta_contable`.
**Pruebas:** cliente contra servidor mock; upsert idempotente; líneas con error no bloquean el resto; consulta de novedades por periodo/sede.
**Compuerta:** sincronización idempotente probada; trazabilidad y catálogos funcionando.
**Dependencia externa:** requiere el contrato de endpoints de Órdenes (pendiente); si aún no llega, se avanza con el importador de archivo y el cliente contra mock.

### S4 — Generar factura consolidada · 2 semanas
**Objetivo:** el *slice* canónico de punta a punta.
**Historias:** HU-06 (factura única por NIT), HU-07 (corte por tipo/periodo), HU-26 (consultar facturas).
**Entregables:** `ServicioFacturacion.generarConsolidada` integrando dominio + persistencia + cálculo; controladores REST (`/facturas/generar`, `/facturas`, `/facturas/{id}`); manejo de errores con `@RestControllerAdvice`.
**Pruebas:** aceptación end-to-end del criterio ejemplo (cliente con 3 sedes, una con alta a mitad de periodo, una con cuota pendiente → cabecera, ítems por sede, prorrateo, IVA, retenciones, totales que cuadran, unicidad respetada).
**Compuerta:** factura consolidada correcta y consultable de extremo a extremo.

### S5 — Ajustes: anulación y notas crédito · 1.5 semanas
**Objetivo:** correcciones sobre facturas emitidas.
**Historias:** HU-19 (anular y refacturar), HU-20 (nota crédito ligada a su factura).
**Entregables:** `ServicioFacturacion.anular`; `ServicioNotaCredito.emitir`; endpoints correspondientes.
**Pruebas:** anular habilita refacturar respetando el índice único; la nota crédito queda ligada a su factura y es consultable.
**Compuerta:** flujos de ajuste probados sin romper unicidad ni totales.

### S6 — Exportación a Siigo y confirmación · 2 semanas
**Objetivo:** producir el archivo plano y cerrar el ciclo con Siigo.
**Historias:** HU-21 (generar lote), HU-22 (descargar), HU-23 (confirmar con número/CUFE), HU-24 (total por NIC).
**Entregables:** `EmisorFacturaOficial` + `ExportadorArchivoPlanoSiigo` con el layout real (asiento de doble partida); `ServicioLote.generar/confirmar`; importador del consecutivo.
**Pruebas:** el archivo generado cuadra (débitos = créditos) y respeta orden/formato de columnas; la confirmación guarda `numero_factura`, `cufe`, `estado_envio`; reporte por NIC correcto.
**Compuerta:** un lote real generado, validado y confirmado extremo a extremo.
**Dependencia externa:** requiere el mapeo de cuentas confirmado, ejemplo con retención y ejemplo F20 (pendientes).

### S7 — Estabilización y no funcionales · 1 semana
**Objetivo:** endurecer y dejar listo para operación.
**Alcance:** RNF de seguridad (secretos, validación, logs sin datos sensibles), observabilidad (logging de negocio, métricas), rendimiento (clientes con 40+ sedes), timeouts/reintentos; resolución de pendientes; documentación final y pruebas de aceptación de usuario (UAT).
**Compuerta:** RNF verificados y checklist de pendientes cerrado o con decisión registrada.

---

## Dependencias entre sprints

- S1 no requiere BD; puede correr en paralelo conceptual con S0 una vez exista el esqueleto.
- S4 depende de S1 (cálculo) y S2 (persistencia).
- S6 depende de S1 (asiento), S2 (persistencia) y S4 (facturas) + del mapeo de cuentas.
- S3 y S6 tienen dependencias externas (endpoints de Órdenes / formatos de Siigo): si un pendiente no está, se avanza con mocks/alternativa y se cierra el detalle al recibir el insumo.

---

## Cronograma (referencia)

| Semana | 1 | 2-3 | 4-5 | 6-7 | 8-9 | 10-11 | 12-13 | 13.5 |
|---|---|---|---|---|---|---|---|---|
| Sprint | S0 | S1 | S2 | S3 | S4 | S5 | S6 | S7 |

Fin de S4 (~semana 9) = primera fase operativa (Órdenes + generación de factura), en línea con lo acordado en la reunión.
