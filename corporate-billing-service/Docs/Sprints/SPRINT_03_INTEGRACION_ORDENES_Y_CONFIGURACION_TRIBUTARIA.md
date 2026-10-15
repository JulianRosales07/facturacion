# Sprint 3 — Integración con Sistema de Órdenes y Configuración Tributaria

- **Servicio:** `corporate-billing-service`
- **Paquetes:** `com.sistemaspalacios.billing.service`, `com.sistemaspalacios.billing.service.ordenes`, `com.sistemaspalacios.billing.infrastructure.ordenes`, `com.sistemaspalacios.billing.infrastructure.file`, `com.sistemaspalacios.billing.repository`
- **Historias de Usuario Cubiertas:** HU-01, HU-02, HU-03, HU-04, HU-05, HU-17, HU-25 / HU-28, HU-27.
- **Estado:** Completado y Verificado en Verde (100% de pruebas unitarias, de integración con Testcontainers/PostgreSQL real y reglas de arquitectura ArchUnit pasando).

---

## 1. Objetivo del Sprint

Poblar y sincronizar los maestros operativos (clientes corporativos, sedes de suscripción, cuotas de instalación y novedades) a partir del Sistema de Órdenes mediante dos canales desacoplados:
1. **Integración HTTP vía API REST** con `RestClient` de Spring, aislamiento mediante puerto de aplicación (`OrderSystemClient` / `ClienteSistemaOrdenes`) y lógica de *upsert* idempotente que no duplica registros al reejecutar.
2. **Ingesta por Archivo Plano / JSONL** (`FileOrderImporter`) como canal alternativo resiliente, con trazabilidad persistente a nivel de línea (`archivo_importado` y `archivo_importado_linea`), almacenamiento del crudo JSONB y manejo de excepciones no bloqueantes.

Adicionalmente, se implementa el servicio de aplicación para la **administración de la configuración tributaria** (`TaxConfigurationService` / `ServicioConfiguracionTributaria`), permitiendo el CRUD de conceptos, vigencias normativas fechadas (`vigente_desde`, `vigente_hasta`) y perfiles tributarios con *override* de porcentaje y clasificación técnica (`INTERNET` vs `SOFTWARE`) por cliente corporativo.

---

## 2. Historias de Usuario Implementadas y Criterios de Aceptación

| ID | Historia de Usuario | Mecanismo de Garantía | Criterio de Aceptación Cumplido |
|---|---|---|---|
| **HU-01** | **Sincronización de maestros y novedades** | `OrderService.syncPeriod(periodCode)` / `ServicioOrdenes.sincronizar` | Consume suscripciones y novedades del periodo desde `OrderSystemClient` y orquesta la creación/actualización en la base de datos local. |
| **HU-02** | **Idempotencia de sincronización (no duplicidad)** | Búsqueda por claves naturales (`nit` para clientes; `(codigo_orden_externo, origen)` para sedes; `(siteId, periodId, updateType)` para novedades) | Reejecutar la sincronización $N$ veces sobre el mismo periodo no genera duplicados de clientes, sedes, cuotas ni novedades. Las modificaciones de datos en Órdenes se actualizan in-place. |
| **HU-03** | **Importación alternativa por archivo** | `FileOrderImporter.importFromLines` / `importFromStringPayload` | Ingiere lotes en formato JSON/JSONL sin requerir conexión en vivo con la API de Órdenes, integrando cada registro a través del núcleo del servicio. |
| **HU-04** | **Consulta y trazabilidad de novedades** | `OrderService.getUpdatesByPeriod` y `getUpdatesByPeriodAndSite` | Permite consultar todas las novedades operativas registradas para un periodo específico o filtrar por sede y periodo para auditoría y prorrateo. |
| **HU-05** | **Manejo de origen y asociación de marca/zona** | Enum `OrderOrigin` (`SPE`, `MEGA_RED`, `ENLACES`) y claves foráneas en `sede_suscripcion` y `cliente_corporativo` | Mapeo explícito del origen técnico de cada orden y conservación de sus códigos de marca y zona para facturación F1 o F20. |
| **HU-17** | **Mapeo de cuentas contables** | Entidad `AccountingAccountMapping` y repositorio `RepositorioMapeoCuentaContable` | Consulta y gestión de cuentas contables para el asiento de doble partida (`INGRESO`, `IVA`, `CARTERA`, `ORDEN`) por tipo de comprobante. |
| **HU-25 / HU-28** | **Administración y resolución de configuración tributaria** | `TaxConfigurationService` y repositorios `RepositorioConceptoTributario`, `RepositorioVigenciaConcepto`, `RepositorioPerfilTributarioCliente` | CRUD de vigencias impositivas con fechas de inicio/fin y asignación por cliente corporativo con *override* de porcentaje pactado y clasificación (`INTERNET`/`SOFTWARE`). |
| **HU-27** | **Trazabilidad resiliente por línea en importaciones** | Tablas `archivo_importado` y `archivo_importado_linea` con JSONB | Cada línea se audita individualmente. Una línea malformada o inválida se marca en estado `ERROR` con su mensaje específico sin detener el procesamiento de las demás líneas válidas del lote. |

---

## 3. Arquitectura y Componentes Implementados

### 3.1. Puertos y DTOs de Integración (`service.ordenes`)
Para preservar la regla estricta de arquitectura en capas (`infrastructure -> service -> domain/repository`) sin generar ciclos entre paquetes:
- **`OrderSystemClient`**: Interfaz de puerto de salida que declara `fetchSubscriptions(periodCode)` y `fetchUpdates(periodCode)`.
- **`ClienteSistemaOrdenes`**: Alias de interfaz en español con métodos por defecto `obtenerSuscripciones` y `obtenerNovedades`.
- **`OrderSyncRecordDto`**: Registro compuesto para ingestión por archivo o sincronización masiva.
- **`SubscriptionOrderDto`**: DTO inmutable para transferir suscripciones desde Órdenes.
- **`OrderUpdateDto`**: DTO inmutable para novedades operativas.
- **`OrderSyncResultDto`**: Objeto resumen con conteo de registros procesados, creados y fallidos.
- **`OrderSystemException`**: Excepción desacoplada de la capa de transporte para fallos de comunicación externa.

### 3.2. Adaptador HTTP (`infrastructure.ordenes`)
- **`HttpOrderSystemClient`**: Implementación basada en Spring `RestClient` con inyección de propiedades (`OrderSystemProperties`), timeouts configurables (connect/read timeout) y encabezado de seguridad `X-API-Key`.
- Contrato HTTP formal marcado como `<PENDIENTE: URLs y contratos finales del equipo de Órdenes>` para rápida adaptación cuando se entregue la API oficial.

### 3.3. Ingestor Resiliente de Archivos (`infrastructure.file`)
- **`FileOrderImporter`**: Procesador de lotes línea por línea que persiste la auditoría en `archivo_importado` (estado `PROCESANDO` -> `PROCESADO`/`ERROR`) y en `archivo_importado_linea` (`PENDIENTE` -> `PROCESADA`/`ERROR`).
- Emplea Jackson `ObjectMapper` con fallback automático `JavaTimeModule` para serialización de fechas ISO-8601.

### 3.4. Servicios de Aplicación (`service`)
- **`OrderServiceImpl`** (implementa `OrderService` y `ServicioOrdenes`):
  - Orquestación transaccional `@Transactional`.
  - Auto-creación de `BillingPeriod` si no existe en la base de datos al momento de sincronizar.
  - Creación y activación automática de `InstallationFee` (`cuota_instalacion`) para sedes con cuotas pendientes.
- **`TaxConfigurationServiceImpl`** (implementa `TaxConfigurationService` y `ServicioConfiguracionTributaria`):
  - Consulta de conceptos (`RETEFUENTE`, `RETEICA`, `RETEIVA`, `ESTAMPILLA`).
  - Filtrado temporal de vigencias impositivas (`findActiveValiditiesAtDate` y `findValiditiesByConceptAndDate`).
  - Asignación y resolución del Value Object inmutable `TaxProfile` para el agregado `CorporateClient`.

### 3.5. Modelo de Dominio y Repositorios JPA
- **`AccountingAccountMapping`** (`mapeo_cuenta_contable`): Entidad JPA con mapeo a enums `tipo_linea_asiento`, `naturaleza_contable` y `tipo_facturacion`.
- **`ImportedFile`** (`archivo_importado`) y **`ImportedFileLine`** (`archivo_importado_linea`): Entidades JPA con columna JSONB para `contenido_crudo`.
- **`RepositorioMapeoCuentaContable`** / **`AccountingAccountMappingRepository`**
- **`RepositorioCuotaInstalacion`** / **`InstallationFeeRepository`**
- **`RepositorioArchivoImportado`** / **`ImportedFileRepository`**
- **`RepositorioArchivoImportadoLinea`** / **`ImportedFileLineRepository`**

---

## 4. Pruebas y Validación de la Compuerta de Cierre

Se ejecutó la suite completa de pruebas del proyecto garantizando el cumplimiento de la compuerta de Definition of Done:

1. **`HttpOrderSystemClientTest`**:
   - Validación contra servidor simulado con `MockRestServiceServer`.
   - Verificación de parseo JSON, encabezados `X-API-Key` y manejo de respuestas 200 OK y 500 Server Error.
2. **`OrderSyncIntegrationTest`**:
   - Prueba de integración contra PostgreSQL real con Testcontainers.
   - Demostración de **idempotencia** al ejecutar dos veces la sincronización de 2 sedes y 1 novedad: 0 clientes duplicados, 0 sedes duplicadas, 0 novedades duplicadas.
   - Demostración de actualización in-place de canon y dirección sobre sedes existentes.
   - Demostración de consultas de novedades por periodo y sede (HU-04).
3. **`FileOrderImporterIntegrationTest`**:
   - Ingesta de archivo con 3 líneas válidas y 1 línea malformada.
   - Verificación de que las 3 líneas válidas se procesan (`PROCESADA`) y se persisten en base de datos.
   - Verificación de que la línea fallida se marca `ERROR` con mensaje descriptivo sin interrumpir el lote (no bloqueante).
   - Verificación de que el archivo queda en estado `PROCESADO` con `registros_con_error = 1` y `total_registros = 4`.
4. **`TaxConfigurationServiceIntegrationTest`**:
   - Validación de catálogos sembrados en `V1`.
   - Creación de vigencias impositivas con fechas escalonadas (cambio normativo a mitad de año) y resolución correcta según fecha de consulta.
   - Asignación de *override* de porcentaje (1.20% para RETEICA territorial) y clasificación `INTERNET`.
   - Resolución íntegra del Value Object `TaxProfile`.
5. **`ArchitectureLayerRulesTest`**:
   - ArchUnit en verde: cero ciclos entre paquetes (`no_cycles_between_packages`), orden estricto de capas (`layered_architecture_is_respected`) y regla de acceso directo denegado entre web y repositorio.
6. **Compilación y Build General**:
   - `./gradlew build` finalizado con éxito (`BUILD SUCCESSFUL`).
