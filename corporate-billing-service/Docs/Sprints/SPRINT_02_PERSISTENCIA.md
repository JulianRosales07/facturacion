# Sprint 2 — Persistencia e Invariantes en Base de Datos

- **Servicio:** `corporate-billing-service`
- **Paquetes:** `com.sistemaspalacios.billing.domain.model`, `com.sistemaspalacios.billing.repository`, `com.sistemaspalacios.billing.infrastructure.persistence`
- **Historias de Usuario Cubiertas:** HU-08, HU-09, HU-29/RF-29.
- **Estado:** Completado y Verificado en Verde (100% pruebas de integración pasando contra PostgreSQL real).

---

## 1. Objetivo del Sprint

Persistir el modelo de dominio en PostgreSQL garantizando la integridad referencial y las invariantes de negocio directamente a nivel de base de datos bajo el patrón **DDD-Lite** (las mismas clases del dominio anotadas como `@Entity`, sin mappers ni DTOs innecesarios), con repositorios Spring Data en español y verificación exhaustiva mediante pruebas de integración sobre PostgreSQL real.

---

## 2. Historias de Usuario Implementadas y Criterios de Aceptación

| ID | Historia de Usuario | Mecanismo de Garantía | Criterio de Aceptación Cumplido |
|---|---|---|---|
| **HU-08** | **Una única factura activa por cliente y periodo** | Índice único parcial en BD:<br>`CREATE UNIQUE INDEX uq_factura_cliente_periodo_activa ON factura(cliente_corporativo_id, periodo_facturacion_id) WHERE estado != 'ANULADA';` | Rechazo inmediato con `DataIntegrityViolationException` si se intenta guardar una segunda factura activa para el mismo cliente y periodo. Si la factura previa se anula (`estado = 'ANULADA'`), el índice habilita de inmediato la refacturación. |
| **HU-09** | **Snapshot fiscal inmutable en cabecera de factura** | Columnas directas en `factura`:<br>`nit_cliente`, `razon_social_cliente`, `direccion_fiscal_cliente`, `email_facturacion_cliente` | Al emitir la factura se congelan los datos fiscales del cliente. Cualquier edición posterior en la tabla `cliente_corporativo` (cambio de razón social, NIT o dirección) no altera los datos de la factura previamente emitida. |
| **HU-29 / RF-29** | **Conservación de histórico por inactivación (Soft Delete)** | Columna `estado estado_registro` (`ACTIVO`, `INACTIVO`) en clientes y sedes | La inactivación preserva el 100% de la información histórica para auditoría y facturación previa. Las consultas operativas filtran registros activos mientras que las consultas históricas/globales mantienen el registro íntegro sin eliminación física. |

---

## 3. Modelo de Persistencia (DDD-Lite)

Se adoptó la regla estricta de **DDD-Lite**: las mismas clases del dominio se mapean con JPA a las tablas en español definidas en `V1__esquema_inicial.sql`.

### 3.1. Entidades de Dominio Anotadas (`domain.model`)

1. **`Invoice` (`factura`)**:
   - Agregado raíz de facturación.
   - Clave primaria: UUID (`id`).
   - Claves foráneas: `cliente_corporativo_id`, `periodo_facturacion_id`, `lote_exportacion_id` (opcional).
   - Snapshot fiscal: `nit_cliente`, `razon_social_cliente`, `direccion_fiscal_cliente`, `email_facturacion_cliente`.
   - Totales oficiales: `valor_bruto`, `valor_descuento`, `valor_subtotal`, `valor_iva`, `valor_total`.
   - Retenciones para recaudo: `valor_retefuente`, `valor_reteica`, `valor_reteiva`, `valor_estampillas`, `valor_total_retenciones`, `neto_a_pagar`.
   - Relación en cascada `@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)` con `item_facturable`.
   - Estado: `estado_factura` (`BORRADOR`, `CONSOLIDADA`, `PAGADA`, `ANULADA`).

2. **`InvoiceItem` (`item_facturable`)**:
   - Ítem individual de la factura consolidada con discriminación de tipo (`tipo_item_facturable`), sede opcional, cantidad, valor unitario, porcentaje y valor de descuento, base gravable, porcentaje y valor de IVA, y valor total.

3. **`CorporateClient` (`cliente_corporativo`)**:
   - Maestro de clientes corporativos con NIT único, razón social, dirección fiscal, correo de facturación, marcas, zonas, tipo de facturación (F1/F20), bandera rápida de retenciones, códigos de integración Siigo y estado (`ACTIVO`, `INACTIVO`).

4. **`SubscriptionSite` (`sede_suscripcion`)**:
   - Sede o suscripción del cliente corporativo con código de orden externo, origen (SPE, MEGA_RED, ENLACES), NIC, canon mensual, fecha de certificación técnica y estado (`ACTIVO`, `INACTIVO`).

5. **`BillingPeriod` (`periodo_facturacion`)**:
   - Periodo mensual de cobro con código único `YYYY-MM` (validado por expresión regular en BD), tipo de corte (`ADELANTADO` o `VENCIDO`), rango de fechas y estado (`ABIERTO` o `CERRADO`).

6. **`OrderUpdate` (`novedad_orden`)**:
   - Novedad operativa asociada a una sede y periodo, con tipo de novedad (ALTA, BAJA, CAMBIO_PLAN, etc.), valor, fecha efectiva e indicador de inclusión en consolidado.

7. **`InstallationFee` (`cuota_instalacion`)**:
   - Control de amortización de instalación con valor total, número de cuotas pactadas, cuotas facturadas, valor por cuota y estado (`PENDIENTE`, `FACTURANDO`, `PAGADA`, `ANULADA`).

8. **`TaxConceptValidity` (`vigencia_concepto`)**:
   - Entidad JPA de vigencia impositiva con concepto tributario (RETEFUENTE, RETEICA, RETEIVA, ESTAMPILLA), clasificación de servicio, municipio, base mínima de retención, porcentaje y rango `[vigente_desde, vigente_hasta]`.

9. **`ExportBatch` (`lote_exportacion`)**:
   - Registro de lote de exportación generado hacia Siigo con periodo, tipo de facturación, tipo de corte, nombre de archivo, total de facturas y estado del lote (`GENERADO`, `ENVIADO`, `CONFIRMADO`, `ERROR`).

10. **`TaxConcept` (`concepto_tributario`)** y **`ClientTaxProfileEntity` (`perfil_tributario_cliente`)**:
    - Catálogos de configuración tributaria y relación contractual de retenciones por cliente corporativo.

---

### 3.2. Conversores JPA de Infraestructura (`infrastructure.persistence`)

Para garantizar compatibilidad transparente entre Java y PostgreSQL:
- **`MoneyConverter`**: `AttributeConverter<Money, BigDecimal>` con `autoApply = true`. Convierte el Value Object inmutable `Money` a `NUMERIC(14,2)` en todas las columnas financieras.
- **`InvoiceStatusConverter`**: Mapea `InvoiceStatus` al enum nativo `estado_factura` de Postgres.
- **`InstallationFeeStatusConverter`**: Mapea a `estado_cuota` de Postgres.
- **`BillableItemTypeConverter`**: Mapea a `tipo_item_facturable` de Postgres.
- **`BillingCutTypeConverter`**: Mapea `BillingCutType` a cadenas `ADELANTADO` y `VENCIDO`.
- **`OrderUpdateTypeConverter`**: Mapea el enum `OrderUpdateType` a IDs 1..7 de `tipo_novedad`.
- **`TaxConceptCodeConverter`**: Mapea `TaxConceptCode` a IDs 1..4 de `concepto_tributario`.

---

## 4. Repositorios Spring Data en Español (`repository`)

Se definieron las interfaces en español bajo el estándar Spring Data JPA:

```
com.sistemaspalacios.billing.repository
├── RepositorioFactura.java
├── RepositorioCliente.java
├── RepositorioSede.java
├── RepositorioNovedad.java
├── RepositorioPeriodo.java
├── RepositorioLote.java
├── RepositorioVigenciaConcepto.java
├── RepositorioConceptoTributario.java
├── RepositorioPerfilTributarioCliente.java
└── InvoiceRepository.java (alias compatible)
```

### Consultas Destacadas:
- `RepositorioFactura.findActiveByClientAndPeriod(clientId, periodId)`: Consulta la factura activa (excluyendo estado `ANULADA`).
- `RepositorioCliente.findByNit(nit)` y `findByStatus(RecordStatus)`: Búsqueda exacta y filtrado por clientes activos.
- `RepositorioSede.findByClientIdAndStatus(clientId, RecordStatus)`: Sedes activas de un cliente.
- `RepositorioVigenciaConcepto.findByConceptCodeAndDate(conceptCode, date)`: Resolución dinámica de vigencias tributarias por fecha efectiva.

---

## 5. Pruebas de Integración y Validación de Invariantes

Clase de prueba: [`PersistenciaInvariantesIntegrationTest.java`](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/src/test/java/com/sistemaspalacios/billing/PersistenciaInvariantesIntegrationTest.java) ejecutada contra PostgreSQL real:

```
PersistenciaInvariantesIntegrationTest
├── S2-01: Guardar y leer agregado de Factura completo con ítems y Money ....................... [PASS]
├── HU-08: El índice único parcial rechaza 2 facturas activas para el mismo cliente y periodo ... [PASS]
├── HU-08: Anular una factura permite crear una nueva factura activa (refacturación) ............ [PASS]
├── HU-09: Snapshot fiscal inmutable en cabecera no cambia al editar datos del cliente .......... [PASS]
├── HU-29/RF-29: Inactivación conserva histórico y permite consultas activas vs históricas ...... [PASS]
└── S2-02: Persistencia de novedades, lotes y vigencias de configuración tributaria ............ [PASS]
```

### 5.1. Detalle de Pruebas de Invariantes:

1. **Prueba HU-08 (Unicidad Parcial):**
   - Se inserta la Factura 1 para (Cliente A, Periodo 2026-12) en estado `DRAFT`.
   - Se intenta insertar la Factura 2 para (Cliente A, Periodo 2026-12) en estado `DRAFT`.
   - **Resultado:** La base de datos rechaza la operación lanzando `DataIntegrityViolationException` causada por `uq_factura_cliente_periodo_activa`.

2. **Prueba HU-08 (Refacturación tras Anulación):**
   - Se anula la Factura 1 (`estado = 'ANULADA'`).
   - Se inserta la Factura 2 para el mismo (Cliente A, Periodo 2026-12).
   - **Resultado:** Inserción exitosa sin violaciones, garantizando el flujo de refacturación.

3. **Prueba HU-09 (Snapshot Fiscal Inmutable):**
   - Se emite factura para el cliente con NIT "900555666-1" y Razón Social "DISTRIBUIDORA ANDINA SAS".
   - Posteriormente se modifica el cliente en BD a NIT "900999888-7" y Razón Social "NUEVA RAZON SOCIAL ANDINA GLOBAL S.A.".
   - **Resultado:** La factura recargada mantiene inalterados los datos fiscales con los que fue emitida originalmente.

4. **Prueba HU-29 / RF-29 (Histórico por Inactivación):**
   - Se pasa el estado de un cliente y su sede a `INACTIVO`.
   - **Resultado:** `findByStatus(ACTIVO)` no los retorna, pero `findById()` y consultas históricas los recuperan íntegros con sus códigos, enlaces y registros intactos.

---

## 6. Verificación de Compuerta de Cierre

| Criterio | Requerimiento | Estado |
|---|---|:---:|
| 1. Pruebas Unitarias de Dominio | 21 pruebas JUnit 5 de Sprint 1 | **PASS** (100%) |
| 2. Pruebas de Integración | 6 pruebas contra PostgreSQL real | **PASS** (100%) |
| 3. ArchUnit | Reglas de capas DDD-Lite y ausencia de ciclos | **PASS** (100%) |
| 4. Historias Validadas | HU-08, HU-09, HU-29/RF-29 | **PASS** |
| 5. Compilación Gradle | `./gradlew build` completo | **BUILD SUCCESSFUL** |

---

## 7. Entregable del Sprint 2

- Persistencia completa y estable del dominio sobre PostgreSQL.
- Invariantes de negocio (unicidad activa por periodo, snapshot inmutable, soft delete) aseguradas por BD.
- Repositorios Spring Data en español listos para ser consumidos por los servicios de orquestación y sincronización (Sprint 3 y 4).
