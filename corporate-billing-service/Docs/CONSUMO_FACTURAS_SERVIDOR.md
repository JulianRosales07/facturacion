# Consumo de facturas desde la carpeta del servidor

## Alcance confirmado — 28 de septiembre de 2026

Este servicio recibe facturas ya emitidas por Siigo. La empresa dejará **PDF y un archivo con datos** en una carpeta compartida y concederá acceso de lectura al servicio. No corresponde a este alcance emitir las facturas, recibir dinero, desarrollar una pasarela ni producir recibos contables de pagos.

Los módulos anteriores de generación, órdenes y recaudo siguen presentes en el repositorio. No forman parte de este flujo. La confirmación de lotes salientes y la lectura de cortes de cartera están desactivadas por defecto en el ciclo horario; su activación requiere una decisión independiente.

## Flujo implementado

1. El servicio comprueba que la carpeta configurada sea accesible. No crea carpetas en el servidor de origen.
2. Cada hora —o al pulsar **Sincronizar Siigo ahora**— busca archivos del primer nivel de la carpeta. Espera 60 segundos desde su última modificación y excluye temporales de Excel.
3. Lee primero los datos. El lector actual acepta el Excel de movimiento contable incluido en `Docs/archivosplanos/movimientocontableFACSEP.xlsx` y el JSON normalizado descrito abajo. Un Excel de consecutivos o saldos no contiene el detalle completo y no crea facturas.
4. Importa documentos F1/F20 con fecha, NIT, consecutivo, cantidades, códigos de producto, detalle, bases, IVA y total. Conserva los importes oficiales y valida que el detalle cuadre. En el movimiento contable, el deudor se toma del asiento de cartera, no de cualquier tercero de un asiento de ingreso.
5. La clave `tipo + consecutivo` evita duplicados, aunque el documento aparezca en otro archivo. Varias facturas del mismo NIT y mes permanecen independientes. Repetir un documento igual es seguro; cambiar su detalle, fecha, NIT, total o un CUFE previamente informado produce un error para revisión y no sobrescribe la factura.
6. Asocia los PDF por identidad exacta: convención F001/F020, CUFE o número y datos coincidentes. Un NIT sin número no basta. Las coincidencias ambiguas quedan pendientes.
7. Valida el PDF y guarda una copia local con nombre basado en SHA-256. No mueve, elimina ni modifica el original. Una nueva versión del contenido recibe otra ruta y conserva la copia anterior.
8. Permite consultar y descargar los documentos desde el panel mediante HTTP autenticado. Si aún no llegó el PDF de una factura importada, su descarga devuelve 404; no fabrica un PDF sustituto.
9. Registra el resultado del ciclo y los archivos con errores. Cada archivo de datos se procesa en su propia transacción: uno inválido no bloquea los demás. Se reintenta en el siguiente ciclo.

Los contadores de **facturas verificadas** incluyen las ya importadas que reaparecen. No equivalen al número de documentos nuevos. La exclusión de ejecuciones simultáneas es por instancia: operar inicialmente con un único proceso de sincronización.

## Configuración del servidor

Variables del proceso que ejecuta el backend:

| Variable | Uso |
| --- | --- |
| `SIIGO_CARPETA_COMPARTIDA_PATH` | Ruta UNC en Windows o ruta montada en Linux. Ejemplo ilustrativo: `\\SERVIDOR\FacturasCorporativas`. Reemplazar por la ruta real. |
| `SIIGO_PDF_LOCAL_PATH` | Carpeta local persistente y escribible para conservar los PDF. Predeterminado: `./storage/siigo-pdfs`. |
| `SIIGO_SYNC_CRON` | Predeterminado: `0 0 * * * *`, cada hora en el reloj del servidor. |
| `SIIGO_ARCHIVO_EDAD_MINIMA` | Segundos de espera tras una modificación; predeterminado: `60`. |
| `SIIGO_CONFIRMAR_LOTES_SALIENTES` | `false` para este alcance de recepción. |
| `SIIGO_CONSUMIR_CARTERA` | `false` para este alcance. El lector de saldos anterior requiere validación independiente antes de activarlo. |

La cuenta del servicio necesita permiso de listar y leer la carpeta de origen, además de los permisos de red. El backend no almacena credenciales SMB ni monta recursos compartidos por sí mismo. En Docker, montar la carpeta de origen como volumen de solo lectura y la carpeta local de PDF como volumen persistente escribible.

El productor debe terminar de escribir los archivos antes de publicarlos, preferiblemente mediante renombrado desde un nombre temporal. La espera y la comprobación de tamaño/fecha reducen el riesgo de leer una copia incompleta, pero no sustituyen ese acuerdo con el productor. Límite por archivo: 20 MB.

Aplicar las migraciones Flyway nuevas con el despliegue. No editar migraciones ya aplicadas.

## Operación y API

Se requiere JWT válido con `ROL_ANALISTA`, `ROL_ADMINISTRADOR` o `ROL_SISTEMA` para la importación y la sincronización. Las consultas del panel mantienen sus permisos correspondientes.

| Operación | Ruta |
| --- | --- |
| Ejecutar lectura inmediata | `POST /api/v1/sincronizacion/ahora` |
| Consultar último ciclo | `GET /api/v1/sincronizacion/estado` |
| Carga manual de datos | `POST /api/v1/siigo/facturas/importar`, multipart, campo `archivo` |
| Listar y filtrar facturas | `GET /api/v1/facturas` |
| Ver detalle | `GET /api/v1/facturas/{id}` |
| Descargar PDF oficial | `GET /api/v1/facturas/{id}/pdf` |

En el panel: **Consultar facturas → Importar facturas Siigo** para carga manual; **Consumir carpeta Siigo** para ir al estado y disparo de sincronización. El PDF se lee siempre desde la carpeta configurada. La carga manual no incorpora un PDF adjunto.

Ejemplo del contrato JSON normalizado; no se afirma que sea el formato nativo que entregará el servidor:

```json
[
  {
    "nit": "900123456",
    "businessName": "Cliente de ejemplo",
    "billingType": "F1",
    "invoiceNumber": "51993",
    "issueDate": "2026-09-01",
    "dueDate": "2026-09-30",
    "cufe": null,
    "total": 238000.00,
    "items": [
      {
        "code": "SERV-1",
        "description": "Servicio mensual",
        "quantity": 1,
        "subtotal": 200000.00,
        "iva": 38000.00
      }
    ]
  }
]
```

`subtotal` e `iva` son importes totales de la línea. El movimiento Excel de ejemplo no incluye una razón social fiable ni vencimiento; esos datos quedan pendientes si no existen ya en el maestro. No se inventan direcciones, nombres, plazos o teléfonos. La búsqueda por razón social depende de recibir ese dato.

## Validación y pendientes externos

La prueba del lector comprueba las **1.209 facturas F1/F20** del movimiento contable de ejemplo y la igualdad entre cada total y sus líneas. Hay pruebas de importación múltiple en el mismo mes, repetición sin duplicados, rechazo de cambios, asociación exacta de PDF, conservación de originales, continuidad tras un archivo inválido y protección de endpoints.

Para ponerlo en servicio falta recibir la ruta y permisos reales, confirmar el formato definitivo de datos y ejecutar una lectura de aceptación contra esa carpeta. Si entregan XML u otro diseño de Excel/CSV, se necesita un adaptador para ese formato: no están cubiertos automáticamente por el lector actual. Tampoco se interpretan anulaciones o cambios legales nuevos a partir de una simple ausencia del archivo; requieren definir su contrato con Siigo.

No se ha validado una conexión real al servidor de la empresa. Los pagos y la integración con SP Pagos quedan fuera de este alcance.
