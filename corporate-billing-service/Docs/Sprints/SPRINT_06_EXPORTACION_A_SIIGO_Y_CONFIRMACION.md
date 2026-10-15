# Sprint 6 — Exportación a Siigo, Confirmación y Clasificación Automática de PDFs

- **Servicio:** `corporate-billing-service`
- **Paquetes:** `com.sistemaspalacios.billing.service`, `com.sistemaspalacios.billing.service.siigo`, `com.sistemaspalacios.billing.infrastructure.siigo`, `com.sistemaspalacios.billing.repository`, `com.sistemaspalacios.billing.web`
- **Historias de Usuario Cubiertas:** HU-21 (Generación de Comprobantes Contables F1/F20), HU-22 (Exportación y Descarga de Plano Siigo), HU-23 (Confirmación de Lote e Ingesta de Consecutivos), HU-24 (Captura, Asociación y Clasificación de PDFs por Cliente Corporativo), HU-29 (Auditoría y Trazabilidad).
- **Estado:** Completado y Verificado en Verde (100% de pruebas unitarias, de integración con PostgreSQL real y reglas de arquitectura ArchUnit pasando).

---

## 1. Objetivo del Sprint

Cerrar el ciclo operativo y contable con **Siigo Pyme** mediante la exportación del movimiento contable de facturación y el consumo automatizado de la carpeta compartida en el servidor de Sistemas Palacios:
1. **HU-21 y HU-22: Exportación de Archivo Plano Oficial:** Generar el archivo de importación de movimiento contable para Siigo bajo un asiento de doble partida balanceado ($\sum \text{Débitos} = \sum \text{Créditos}$), con $N$ líneas de ingreso por sede/suscripción, 1 línea de IVA 19% y 1 línea de cartera corporativa por el total oficial. Las retenciones fiscales **no se incluyen en el asiento ni en el valor oficial** (solo aplican sobre el neto a pagar). Soporta comprobantes **F1** (código 1, prefijo `FESP`) y **F20** (código 20, prefijo `FEMG`).
2. **HU-23: Confirmación de Lote e Ingesta desde Carpeta Compartida:** Consumir desde el servidor de Sistemas Palacios los archivos planos generados por Siigo con los consecutivos procesados (`numero_factura`, `cufe`, `estado_envio`), actualizando las facturas en base de datos y cambiando el estado del lote a `CONFIRMADO`.
3. **HU-24: Motor de Clasificación Automática de PDFs por Cliente Corporativo:** Recorrer la carpeta compartida del servidor e identificar a qué cliente corporativo y factura pertenece cada PDF oficial mediante una estrategia multinivel:
   - **Nivel 1 (Regex por Nombre):** decodifica la convención Siigo `^F(?<tipo>\d{3})(?<consecutivo>\d+?)0000\.PDF$`, normaliza ceros a la izquierda y consulta la factura en BD por tipo y número, asociándola de inmediato a su `cliente_corporativo_id` y `nit_cliente`.
   - **Nivel 2 (Fallback con Apache PDFBox):** si el archivo tiene un nombre no estándar o fue alterado, inspecciona el texto del PDF mediante expresiones regulares extrayendo el **CUFE** o el **NIT del cliente**, resolviendo la factura en BD.
   - **Nivel 3 (Persistencia y Consulta REST):** almacena `factura.ruta_pdf` y habilita su descarga a través de `GET /api/v1/facturas/{id}/pdf` y `GET /api/v1/clientes/{nit}/facturas/{periodo}/pdf`.

---

## 2. Historias de Usuario Implementadas y Criterios de Aceptación

| ID | Historia de Usuario | Mecanismo de Garantía | Criterio de Aceptación Cumplido |
|---|---|---|---|
| **HU-21** | **Comprobante contable Siigo (F1 / F20)** | `AccountingEntryGenerator`, `SiigoFlatFileExporter`, `EmisorFacturaOficial` | Genera el comprobante de doble partida estricto: ingresos por sede + IVA = cartera. F1 utiliza código comprobante 1 y F20 código 20. Sin retenciones en las líneas ni en la cabecera. |
| **HU-22** | **Generación y descarga de archivo plano** | `BatchService.generateBatch`, `getBatchFile`, `BatchController` | Agrupa facturas consolidadas activas del corte en `lote_exportacion` (`ExportBatch`), genera el archivo delimitado de 28 columnas y lo entrega vía `GET /api/v1/lotes/{id}/archivo`. |
| **HU-23** | **Confirmación de lote desde carpeta compartida** | `BatchService.confirmBatchFromSharedFolder`, `SiigoConfirmationParser` | Escanea los archivos de consecutivos en la ruta del servidor (`siigo.carpeta-compartida.path`), parsea los registros y actualiza `numero_factura`, `cufe` y `estado_envio`. Pasa el lote a `CONFIRMADO`. |
| **HU-24** | **Clasificación y asociación automática de PDFs** | `SiigoPdfImporterService`, `PdfBoxMetadataExtractor`, `ImportadorPdfSiigo` | Emparejamiento Nivel 1 por regex sobre convención Siigo (`F001...519930000.PDF` $\rightarrow$ FESP 51993) y Nivel 2 con fallback de PDFBox (lectura de CUFE / NIT). Asocia la ruta a la factura y cliente corporativo. Expone `GET /facturas/{id}/pdf`. |
| **HU-29** | **Trazabilidad y auditoría de lotes** | `ExportBatch`, `Invoice`, timestamps y loggers de negocio | Registra `generado_en`, `confirmado_en`, trazabilidad de archivos procesados y no bloquea el procesamiento ante PDFs sin correspondencia. |

---

## 3. Arquitectura y Componentes Implementados

```
                             Carpeta Compartida en Servidor
                             (siigo.carpeta-compartida.path)
                             ┌──────────────────────────────┐
                             │ • Planos Consecutivos (*.csv)│
                             │ • Facturas Oficiales (*.pdf) │
                             └──────────────┬───────────────┘
                                            │
               ┌────────────────────────────┴────────────────────────────┐
               ▼                                                         ▼
    [SiigoConfirmationParser]                               [SiigoPdfImporterService]
   Lee: Consecutivo, CUFE, NIT                               Estrategia Multinivel:
               │                                            ┌────────────┴────────────┐
               ▼                                            ▼                         ▼
      [BatchService.confirm]                       [Nivel 1: Regex Nombre]   [Nivel 2: PDFBox Fallback]
   • Actualiza numero_factura                       ^F(\d{3})(\d+?)0000.pdf     Extrae CUFE / NIT
   • Actualiza cufe / estado                                │                         │
   • Lote -> CONFIRMADO                                     └────────────┬────────────┘
               │                                                         │
               └────────────────────────────┬────────────────────────────┘
                                            ▼
                               [RepositorioFactura / BD]
                               • Vinculación con Cliente Corporativo
                               • Asignación de factura.ruta_pdf
                               • Endpoints REST listos para consumo
```

### 3.1. Puertos de Integración Siigo (`service.siigo`)
- **`OfficialInvoiceEmitter`** y **`EmisorFacturaOficial`**: Puerto de exportación de movimiento contable (`byte[] exportBatch(ExportBatch batch, List<Invoice> invoices)`).
- **`SiigoSharedFolderReader`**: Puerto para verificación de acceso y listado de archivos planos y PDFs en el servidor.
- **`PdfMetadataExtractor`**: Puerto para extracción de CUFE, NIT y número de documento desde el contenido de un PDF.
- **`SiigoConfirmationParser`**: Parser tolerante que admite delimitadores (`;`, `,`, `|`, `\t`) o JSON estructurado.
- **`SiigoConfirmationRecord`**: DTO inmutable con datos de confirmación oficial.

### 3.2. Adaptadores de Infraestructura (`infrastructure.siigo`)
- **`SiigoFlatFileExporter`** (`@Primary`):
  - Formatea 28 columnas por fila cumpliendo la especificación de Siigo.
  - Genera encoding `ISO-8859-1` estándar para la importación contable.
  - Verifica partida doble en cada comprobante con `AccountingEntryGenerator`.
- **`LocalSiigoSharedFolderReader`**:
  - Lee el directorio configurado en `${siigo.carpeta-compartida.path:./storage/siigo_compartida}`.
  - Crea automáticamente el directorio si no existe y verifica permisos de lectura.
- **`PdfBoxMetadataExtractor`**:
  - Implementación basada en Apache PDFBox 3.x (`Loader.loadPDF`).
  - Aplica expresiones regulares para hashes CUFE/CUDE (64 a 96 caracteres hex) y patrones de NIT (`\d{7,10}(-\d)?`).

### 3.3. Servicios de Aplicación (`service`)
- **`BatchService`** / **`ServicioLote`**:
  - `generateBatch`: Crea el lote enlazando las facturas consolidadas activas del periodo y tipo.
  - `getBatchFile`: Retorna los bytes del archivo plano oficial.
  - `confirmBatch`: Aplica números de factura, CUFE y estado devueltos por Siigo, marcando el lote como `CONFIRMADO`.
  - `confirmBatchFromSharedFolder`: Consume automáticamente los planos de confirmación presentes en la carpeta del servidor.
- **`SiigoPdfImporterService`** / **`ImportadorPdfSiigo`**:
  - `processSharedFolderPdfs`: Escanea y procesa todos los PDFs en cola.
  - `classifyAndLinkPdf`: Clasifica individualmente un PDF usando el algoritmo en 2 niveles y actualiza `factura.ruta_pdf`.

### 3.4. Controladores REST y DTOs (`web`)
- **`BatchController`** (`/api/v1/lotes` y `/lotes`):
  - `POST /generar`: Genera lote (HTTP 201).
  - `GET /{id}`: Consulta cabecera del lote.
  - `GET /{id}/archivo`: Descarga el archivo plano (`Content-Disposition: attachment`).
  - `POST /{id}/confirmar`: Confirma lote (vía payload o desde la carpeta compartida).
  - `POST /procesar-carpeta`: Dispara la ingesta y clasificación de PDFs en la carpeta.
- **`InvoiceController`**:
  - `GET /api/v1/facturas/{id}/pdf`: Sirve el PDF oficial capturado de Siigo (con fallback a la representación gráfica generada si el PDF aún no ha sido importado).
  - `GET /api/v1/clientes/{nit}/facturas/{periodo}/pdf`: Consulta y descarga directa de la factura PDF por cliente y periodo.

---

## 4. Matriz de Pruebas Automatizadas

| Suite de Pruebas | Alcance y Escenarios Validados | Resultado |
|---|---|---|
| **`SiigoFlatFileExporterTest`** | • Layout oficial F1 con 28 columnas por fila.<br>• Balance estricto de partida doble ($\sum \text{Débitos} = \sum \text{Créditos}$).<br>• Verificación de que las retenciones NO aparecen en el plano contable. | **100% PASANDO** |
| **`SiigoPdfClassifierTest`** | • Nivel 1: Regex sobre convención Siigo (`F001...519930000.PDF` $\rightarrow$ FESP 51993; `F020...0002870000.PDF` $\rightarrow$ FEMG 287).<br>• Nivel 2: Extracción con Apache PDFBox de CUFE, NIT y consecutivo sobre PDF generado en memoria. | **100% PASANDO** |
| **`SiigoBatchIntegrationTest`** | • Generación de lote real con facturas consolidadas activas.<br>• Descarga de plano oficial verificando cabecera y totales.<br>• Confirmación de lote leyendo archivo plano simulado en la carpeta compartida del servidor.<br>• Actualización atómica de `numero_factura`, `cufe`, `estado_envio` y lote `CONFIRMADO`.<br>• Escaneo y clasificación automática de PDF oficial por cliente corporativo asociando `ruta_pdf`. | **100% PASANDO (PostgreSQL Real)** |
| **`ArchitectureLayerRulesTest`** | Reglas ArchUnit: respeto de capas `web -> service -> domain/repository` e `infrastructure -> service/domain`, sin ciclos de paquetes. | **100% PASANDO** |

---

## 5. Compuerta de Salida del Sprint 6

- [x] Layout de archivo plano contable Siigo (F1 / F20) generado con doble partida balanceada y sin retenciones.
- [x] Lotes de exportación gestionados (`lote_exportacion`) y descargables vía API REST.
- [x] Consumo automatizado de archivos planos de confirmación desde la carpeta compartida en el servidor.
- [x] Motor de clasificación automática de facturas y PDFs por cliente corporativo (Nivel 1 Regex + Nivel 2 Fallback PDFBox).
- [x] Endpoints REST de descarga de PDFs individuales y por cliente corporativo operativos.
- [x] 100% de pruebas en verde y build compilando sin advertencias críticas (`./gradlew build`).
