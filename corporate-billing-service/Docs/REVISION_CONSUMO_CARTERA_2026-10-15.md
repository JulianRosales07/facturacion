# Revisión del consumo de cartera — 15 de octubre de 2026

## Dictamen

El servicio contiene lectura de `saldos_de_cartera.xlsx`, importación de documentos F1/F20, asociación de PDF, consultas y módulos de pagos. **No se considera validado para cobros en producción.** Leer el Excel no demuestra que los importes publicados sean correctos durante todo el ciclo de abonos, actualización y pago.

El usuario confirmó que la fuente de producción será **saldos de cartera**. El README y `CONSUMO_FACTURAS_SERVIDOR.md` aún describen un alcance centrado en recepción de facturas y contienen afirmaciones que no reflejan el lector actual. No se verificaron procesos internos de Claro, Tigo o Movistar; la comparación se limita a las capacidades necesarias para gestionar obligaciones y recaudos.

## Hallazgos prioritarios

| Prioridad | Evidencia en el código | Consecuencia y corrección requerida |
| --- | --- | --- |
| P1 | `SecurityConfig.java:54`: `auth.enabled` tiene valor predeterminado `false`; en ese modo se admite cualquier petición y se asigna un usuario local con permisos. `application.yml` no define esa propiedad. | Un despliegue sin configuración adicional puede exponer datos y operaciones financieras. Restringir el modo de desarrollo a un perfil explícito y exigir autenticación en los demás; verificar 401/403 en el despliegue real. |
| P1 | `SiigoOfficialInvoiceReader.java:110-132` convierte SALDO en total, base y un concepto genérico con IVA cero. `OfficialInvoiceImportService.java:142` marca disponibilidad de pago; `FacturaSpPagosDto.java:254` no exige detalle oficial validado. | Antes de recibir un PDF consistente se publica una estructura provisional como factura cobrable. Ejemplo: factura original de 119.000, abono de 50.000 y saldo de 69.000: el Excel aislado no permite afirmar que el total original sea 69.000 ni que el IVA sea cero. Separar saldo oficial de documento oficial; representar explícitamente información desconocida y bloquear liquidaciones que requieran bases/IVA aún no confirmados. |
| P1 | `OfficialInvoiceImportService.java:103` devuelve sin cambios una factura existente al reconocer una fila de cartera. `application.yml` desactiva `siigo.sync.consumir-cartera` por defecto. | Un Excel actualizado con un abono no actualiza el saldo por la ruta principal. Las filas con saldo cero se omiten en el lector de documentos. Definir una única ingesta de cartera que aplique actualizaciones, ceros y conflictos con trazabilidad; no basta con activar la opción sin revisar el lector de cortes. |
| P1 | `PoiXlsxCarteraReader.java:95-110` usa la fecha de emisión más reciente como fecha de corte; `CarteraSyncService` la usa para ordenar snapshots. `Invoice.applySiigoBalance` acepta fechas iguales. | Dos extractos generados en momentos diferentes pueden tener exactamente la misma fecha máxima de factura. Un extracto antiguo puede sobrescribir uno reciente. Exigir fecha/versión de generación confiable del productor y definir desempates y rechazo de cortes obsoletos. |
| P1 | `SiigoOfficialInvoiceReader.java:115-118` sustituye fecha inválida/ausente por hoy y vencimiento por emisión + 15 días. | Puede inventar periodos y mora. Rechazar la fila o conservar el dato desconocido con una incidencia; probar fechas inválidas y ausentes. |
| P1 | `SiigoOfficialInvoiceReader.java:131` usa `putIfAbsent` para tipo/consecutivo, sin comparar las filas repetidas. | Dos filas del mismo documento con distinto saldo/NIT quedan reducidas a la primera sin avisar. Definir si representan cuotas, repeticiones o conflictos; rechazar contradicciones hasta conocer ese contrato. |
| P2 | `IncomingInvoiceQueryService.searchGroupedByNit` carga todas las facturas coincidentes antes de paginar. `SpPagosQueryServiceImpl` vuelve a consultar la cartera del cliente por cada documento y consulta pagos/notas por factura. | Memoria y consultas crecen con el histórico. Paginar clientes en base de datos y obtener sus facturas y agregados por lotes; medir con un volumen representativo antes de introducir cachés u otra infraestructura. |
| P2 | `ScheduledSiigoSyncJob` usa un `AtomicBoolean` local. Hay índice único por documento externo en V13, pero no coordinación distribuida de ciclos. | Varias réplicas pueden importar a la vez y competir por clientes, periodos y auditorías. Mantener un único ejecutor inicialmente; añadir coordinación en base de datos y pruebas de reintento/concurrencia antes de escalar ejecutores. El índice único sigue siendo necesario. |

El completado desde PDF en `OfficialInvoiceDetailService` reduce parte del riesgo de documentos provisionales, conserva el saldo cuando el total difiere y evita cambiar detalle con pagos. **No elimina la ventana anterior a la llegada/validación del PDF**, ni actualiza por sí mismo todos los cortes posteriores de cartera.

## Controles existentes que conviene conservar

- `Money` usa `BigDecimal`, escala de dos decimales y `HALF_UP`.
- La importación comprueba que bases e IVA de los ítems sumen el total informado. Esta igualdad no valida la procedencia del total cuando todo se construyó desde un saldo.
- Hay clave externa tipo/consecutivo e índice único en PostgreSQL.
- La entrada de datos tiene demarcación transaccional por archivo y auditoría de errores independiente.
- El módulo de pagos implementa claves de idempotencia, huella de solicitud y bloqueos pesimistas. Estos controles no constituyen certificación integral del recaudo.
- La sincronización controla tamaño, cambios durante lectura y hash; la ausencia de un documento en un corte no se interpreta por defecto como pago.

## Organización recomendada

Hacer cambios pequeños y verificables, en este orden:

1. Asegurar configuración de autenticación y separar pruebas de cualquier base compartida.
2. Especificar el contrato real de cartera: columnas obligatorias, identidad, cuotas, saldo cero, fecha de corte, extractos parciales y política de errores. Mantener ejemplos anonimizados.
3. Separar adaptadores de movimiento contable y cartera. Usar un modelo de fila de cartera con saldo y fecha de corte, sin fabricar ítems tributarios.
4. Centralizar la conciliación de saldos y la decisión de si un documento puede pagarse; reutilizarlas desde consulta, simulación y registro.
5. Mantener el completado del documento desde PDF como un flujo separado y auditable, con discrepancias explícitas.
6. Optimizar consultas y paginación después de estabilizar la exactitud financiera.
7. Delimitar módulos históricos de generación/exportación y retirar únicamente los confirmados como fuera de uso. Unificar repositorios e interfaces duplicadas en incrementos, conservando contratos públicos y tablas españolas.
8. Actualizar README y guía operativa para que describan el flujo de cartera confirmado.

## Aceptación mínima del objetivo principal

- Leer el Excel real y conciliar cantidad de obligaciones y suma de saldos contra un control independiente de contabilidad, por NIT y documento.
- Repetir el archivo sin duplicar; recibir un archivo nuevo con abono y reflejar el nuevo saldo; aplicar un saldo cero explícito sin depender de la desaparición de la fila.
- Mantener una factura original de 119.000 y saldo de 69.000 como importes distintos; completar base/IVA desde fuente oficial validada.
- Resolver o rechazar explícitamente duplicados contradictorios, cuotas y documentos con errores; nunca conservar silenciosamente la primera fila contradictoria.
- Rechazar fechas inventadas y cortes obsoletos, incluso con las mismas facturas y fechas de emisión.
- Probar PDF ausente, contradictorio y tardío; pagos y retenciones no deben basarse en detalle provisional sin una regla aprobada.
- Probar pagos repetidos y concurrentes; registrar el efecto de un pago local aún no reflejado por Siigo.
- Verificar permisos, migraciones sobre PostgreSQL aislado y recuperación tras fallo de archivo o proceso.

## Verificación de esta revisión

Se inició `gradlew.bat test`, con acceso autorizado a la caché de Gradle. Compilación y recursos estaban actualizados. La ejecución reportó cuatro fallos y después permaneció varios minutos sin nueva salida; se interrumpió con Ctrl+C y terminó con código 1, sin resumen final de Gradle. No se acredita una ejecución completa exitosa:

- `PruebaLecturaCarpetaServerTest`, flujo Excel + PDF hacia SP Pagos: falla la aserción `pagable == true` en la línea 157.
- `SiigoBatchIntegrationTest`, clasificación de PDF: UUID esperado distinto al obtenido, línea 246.
- `SiigoBatchIntegrationTest`, exportación de lote: esperaba 1 factura y obtuvo 42, línea 184.
- `ReportControllerIntegrationTest`, reporte NIC: esperaba 2 y obtuvo 3, línea 174.

Los números y UUID inesperados justifican investigar aislamiento y datos previos, pero no demuestran por sí solos la causa. No se alteraron aserciones para forzar resultados verdes. Los XML encontrados en `build/test-results/test` tenían fecha anterior a esta ejecución: no se usaron para afirmar un total actualizado de pruebas aprobadas.

`git status` también falló al resolver el repositorio padre; no se reparó ni modificó la configuración Git durante esta revisión. La suite contiene una prueba que escribe `Docs/server/factura_51993_sp_pagos.json`: ese archivo puede haber sido regenerado como efecto de ejecutar las pruebas.

No se modificaron código Java, migraciones ni reglas financieras. Se entrega una revisión y un orden de corrección. No se comprobó acceso al servidor de producción ni se certificaron cumplimiento tributario, disponibilidad o capacidad bajo carga.
