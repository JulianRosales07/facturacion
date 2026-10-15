# Revisión del Excel desde el panel web

Fecha: 15 de octubre de 2026. Revisión del código local y ejecución de pruebas de lectores. No se accedió a un panel desplegado ni se registraron pagos.

## Dictamen

**Existe el recorrido para subir el Excel desde el panel, pero no está validado para operar cobros reales con saldos confiables.** El riesgo principal es funcional y financiero; reorganizar archivos por sí solo no lo resuelve.

Se evaluaron capacidades de importación, consulta y conciliación necesarias para cobrar servicios de internet. No se investigaron ni se certificaron los procesos internos de Claro, Tigo o Movistar.

## Recorrido confirmado

1. En «Facturas recibidas», `invoice-list.component.html` ofrece «Importar archivo» y acepta `.xlsx`, `.xls`, `.xlsm` y `.json`.
2. `InvoiceListComponent.importFile` comprueba extensión y tamaño de 20 MiB.
3. `InvoiceService.importOfficialInvoices` envía `FormData`, campo `archivo`, a `/api/v1/siigo/facturas/importar` con la configuración actual de `environment.apiUrl`.
4. `OfficialInvoiceImportController` exige `IMPORTS_EXECUTE` o rol de sistema y entrega los bytes a `IncomingFileIntakeService`.
5. El servicio calcula SHA-256, invoca la importación transaccional y registra auditoría de resultado o rechazo.
6. `SiigoOfficialInvoiceReader` reconoce movimiento contable y saldos de cartera; `OfficialInvoiceImportService` identifica documentos por tipo/consecutivo y persiste las facturas.
7. El panel vuelve a consultar las facturas recibidas.

La pantalla de importación de órdenes utiliza `/importaciones/archivo`: es otro contrato. Un Excel de órdenes no debe interpretarse como un corte de saldos de cartera.

## Hallazgos que bloquean la confianza financiera

| Prioridad | Evidencia | Efecto |
| --- | --- | --- |
| P1 | `OfficialInvoiceImportService.java:103-105`: una fila de cartera de un documento existente retorna la factura sin aplicar el nuevo saldo. El servicio de recepción web no invoca `CarteraSyncService`. | Cargar un Excel actualizado desde el panel puede terminar con éxito y conservar una deuda anterior. La actualización separada de carpeta no resuelve el contrato de carga web. |
| P1 | `SiigoOfficialInvoiceReader.java:110-128`: usa SALDO como total y base de un concepto genérico con IVA cero. `OfficialInvoiceImportService.java:142` marca disponibilidad y `FacturaSpPagosDto.java:254` no exige detalle oficial confirmado al calcular `pagable`. | Se confunden obligación original y saldo. Una factura de 119.000 con abono de 50.000 puede entrar como documento de 69.000 con IVA cero; el Excel de saldos no acredita esos datos tributarios. La propiedad `pagable` tampoco acredita por sí sola que se haya validado el detalle para liquidar un pago. |
| P1 | `SiigoOfficialInvoiceReader.java:111`: omite saldos menores o iguales a cero. | La ruta web no puede aplicar una cancelación de deuda informada explícitamente como saldo cero. No debe deducirse pago a partir de la desaparición de una fila. |
| P1 | `SiigoOfficialInvoiceReader.java:115-118`: reemplaza fecha ausente o inválida por hoy y vencimiento por emisión + 15 días. | Inventa datos que afectan periodos y vencimientos. Deben rechazarse o representarse como desconocidos con incidencia explícita. |
| P1 | `SiigoOfficialInvoiceReader.java:131`: `putIfAbsent` por tipo/consecutivo. | Filas contradictorias con el mismo documento se reducen silenciosamente a la primera. Debe definirse el tratamiento de cuotas y rechazarse la contradicción de NIT/saldo hasta tener un contrato válido. |
| P1 | `SecurityConfig.java:54,77`: autenticación deshabilitada por defecto y acceso general con usuario local privilegiado. | La anotación de permisos del endpoint no protege un despliegue que use ese modo. Exigir autenticación fuera de un perfil local explícito y probar 401/403. |

También se encontró una contraseña de base de datos literal en la configuración. Debe eliminarse el valor predeterminado y suministrarse por un mecanismo de secretos; si corresponde a una credencial real, debe rotarse. No se reproduce aquí su contenido.

## Escalabilidad y organización

- **Una sola conciliación de cartera:** tanto la carga web como la carpeta deben invocar el mismo caso de uso. Separar el adaptador de movimiento contable del adaptador de cartera; evitar que ambos produzcan una factura con semánticas distintas bajo el mismo modelo.
- **Separar hechos financieros:** documento original, detalle oficial, saldo del corte y pagos locales pendientes de reflejarse en Siigo. Centralizar la política de disponibilidad para consulta, simulación y registro de pago.
- **Versionar el corte con información del productor:** `PoiXlsxCarteraReader` usa la fecha máxima de emisión como fecha de corte. `Invoice.applySiigoBalance` admite fechas iguales. Dos extractos distintos pueden compartir esa fecha y un saldo viejo puede reemplazar uno nuevo. No basta con activar la sincronización de cartera.
- **Paginar en PostgreSQL:** `IncomingInvoiceQueryService.java:92-97` carga todos los resultados y agrupa antes de paginar por NIT. Consultar primero la página de clientes y después sus documentos/agregados por lotes.
- **Coordinar procesos al aumentar réplicas:** el bloqueo `AtomicBoolean` de `ScheduledSiigoSyncJob` solo coordina una JVM. Conservar las restricciones únicas y añadir coordinación entre instancias antes de ejecutar múltiples sincronizadores.
- **Alinear el resultado web con la operación:** actualmente el endpoint devuelve IDs y el panel anuncia «facturas verificadas». Conviene devolver creadas, actualizadas, repetidas, omitidas y rechazadas, más los totales de control y el identificador del corte. Reconocer un documento repetido no significa haber conciliado su saldo.
- **Esquema controlado:** el perfil PostgreSQL predeterminado configura `ddl-auto: update`. Usar migraciones versionadas y validación del esquema en producción.
- **Delimitar módulos históricos:** conservar o retirar generación, exportación y órdenes según sus consumidores reales. No borrar código por apariencia ni mezclar esa limpieza con cambios de reglas financieras.

## Criterios para aceptar el flujo desde el panel

1. Comparar número de obligaciones y suma de saldos, por documento y NIT, con un control independiente del Excel real.
2. Subir dos veces el mismo archivo: no duplicar documentos ni movimientos.
3. Subir un corte posterior con un abono: conservar el total original y actualizar solo el saldo correspondiente.
4. Aplicar un saldo cero explícito, sin tratar una ausencia como pago.
5. Rechazar cortes antiguos, incluso cuando comparten fechas de emisión con el corte vigente.
6. Detectar filas contradictorias, fechas inválidas y columnas requeridas; mostrar incidencias comprensibles sin anunciar una conciliación completa cuando no ocurrió.
7. Verificar PDF ausente, tardío o contradictorio y bloquear las liquidaciones que dependan de bases tributarias sin confirmar.
8. Probar pagos repetidos y concurrentes y el intervalo hasta que Siigo refleje el recaudo local.
9. Probar permisos reales, rollback y reintento después de fallos, migraciones y recuperación en PostgreSQL aislado.
10. Verificar el recorrido con navegador sobre el entorno de pruebas y medir consultas con volumen representativo.

## Verificación ejecutada en esta revisión

Comando: `gradlew.bat test --tests '*SiigoOfficialInvoiceReaderTest' --tests '*PoiXlsxCarteraReaderTest'`.

Resultado: **BUILD SUCCESSFUL**. Los XML de las dos clases seleccionadas informan **10 pruebas, 0 fallos, 0 errores y 0 omitidas**. Incluyen lectura del Excel de cartera de ejemplo y casos del lector de cortes. No se contabilizan otros XML existentes como resultados de esta ejecución.

No se ejecutó la suite completa: hay pruebas de integración con `@ActiveProfiles("postgres")` y valores predeterminados de conexión local; no quedó acreditado su aislamiento respecto de datos existentes. Estas diez pruebas no validan persistencia, pagos, seguridad del despliegue, carga ni navegación web. No se modificaron reglas financieras, código de aplicación ni migraciones durante esta revisión.

Orden recomendado: cerrar autenticación/configuración; definir el contrato de cartera; unificar conciliación web/carpeta con pruebas de abonos y ceros; separar documento y saldo; verificar pagos; optimizar consultas; retirar código confirmado fuera de uso.
