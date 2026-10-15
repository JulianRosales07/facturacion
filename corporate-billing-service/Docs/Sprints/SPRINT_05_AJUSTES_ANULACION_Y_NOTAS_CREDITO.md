# Sprint 5 — Ajustes: Anulación y Notas Crédito

- **Servicio:** `corporate-billing-service`
- **Paquetes:** `com.sistemaspalacios.billing.domain.model`, `com.sistemaspalacios.billing.infrastructure.persistence`, `com.sistemaspalacios.billing.repository`, `com.sistemaspalacios.billing.service`, `com.sistemaspalacios.billing.web`
- **Historias de Usuario Cubiertas:** HU-19 (Anular y Refacturar), HU-20 (Emisión y Consulta de Notas Crédito ligadas).
- **Estado:** Completado y Verificado en Verde (100% pruebas de integración pasando contra PostgreSQL real y ArchUnit sin advertencias).

---

## 1. Objetivo del Sprint

Proveer el circuito integral para gestionar correcciones y ajustes sobre facturas emitidas sin romper la integridad contable, la partida doble ni las invariantes de unicidad del sistema:
1. **HU-19: Anulación y Refacturación:** Marcar la factura emitida como `ANULADA` registrando obligatoriamente el motivo y la fecha/hora exacta (`voidedAt`), liberando la restricción del índice único parcial `uq_factura_cliente_periodo_activa` para habilitar de inmediato la refacturación limpia del cliente en el mismo periodo.
2. **HU-20: Notas Crédito Ligadas (`documento_ajuste`):** Emisión y consulta de notas crédito asociadas contractualmente a su factura original y a sus ítems facturables, garantizando el recálculo financiero de base gravable, IVA (19%) y total neto con `Money` / `BigDecimal` (RoundingMode.HALF_UP).

---

## 2. Historias de Usuario Implementadas y Criterios de Aceptación

| ID | Historia de Usuario | Mecanismo de Garantía | Criterio de Aceptación Cumplido |
|---|---|---|---|
| **HU-19** | **Anulación y refacturación** | `BillingService.voidInvoice` / `ServicioFacturacion.anular` e índice `uq_factura_cliente_periodo_activa` | Marca estado `ANULADA` con motivo y timestamp. El índice único en PostgreSQL `WHERE estado != 'ANULADA'` permite emitir una nueva factura consolidada activa para el mismo cliente y periodo. Rechaza re-anulación con `IllegalStateException`. |
| **HU-20** | **Emisión de Notas Crédito ligadas** | `CreditNoteService.issueCreditNote` / `ServicioNotaCredito.emitir`, entidades `AdjustmentDocument` e `AdjustmentDocumentItem` | Crea el registro en `documento_ajuste` con FK `factura_id`, estado `APPLIED` y consecutivo `NC-...`. Calcula subtotales, IVA y total neto. Soporta nota crédito total (clonando ítems de factura) y parcial (ítems específicos con validación de existencia). Rechaza notas sobre facturas anuladas o borradores, y rechaza valores netos superiores al total de la factura. |
| **HU-20** | **Consulta de Notas Crédito** | `CreditNoteService.getCreditNotesByInvoice` y `getCreditNoteById` | Permite consultar todas las notas crédito de una factura o el detalle de una nota específica vía REST y portal web. |

---

## 3. Arquitectura y Componentes Implementados

### 3.1. Dominio y Modelo de Persistencia (`domain.model`)
- **`AdjustmentDocumentType`**: Enum mapeado al tipo PostgreSQL `tipo_documento_ajuste` (`CREDIT_NOTE` -> `NOTA_CREDITO`, `DEBIT_NOTE` -> `NOTA_DEBITO`).
- **`AdjustmentDocumentStatus`**: Enum mapeado a `estado_documento_ajuste` (`DRAFT` -> `BORRADOR`, `APPLIED` -> `APLICADO`, `VOIDED` -> `ANULADO`).
- **`AdjustmentDocument`** (`documento_ajuste`):
  - Raíz de agregado para notas crédito y débito.
  - Campos: `id`, `invoiceId` (`factura_id`), `type`, `documentNumber`, `reason`, `grossAmount`, `discountAmount`, `subtotalAmount`, `ivaAmount`, `netAmount`, `status`, timestamps.
  - Relación en cascada `@OneToMany` con `AdjustmentDocumentItem`.
- **`AdjustmentDocumentItem`** (`item_documento_ajuste`):
  - Línea de detalle del ajuste con vínculo opcional a `invoiceItemId` (`item_facturable_id`), cantidad, precio unitario, descuento, base gravable, porcentaje de IVA (19%), valor de IVA y total.
- **`Invoice`**:
  - Enriquecido con validación de estado: prohíbe re-anular una factura ya anulada y métodos auxiliares `isVoided()` y `canBeAdjusted()`.

### 3.2. Conversores JPA de Infraestructura (`infrastructure.persistence`)
- **`AdjustmentDocumentTypeConverter`**: Conversor bidireccional auto-aplicado entre `AdjustmentDocumentType` y el tipo enum nativo de Postgres.
- **`AdjustmentDocumentStatusConverter`**: Conversor bidireccional auto-aplicado entre `AdjustmentDocumentStatus` y `estado_documento_ajuste`.

### 3.3. Repositorios Spring Data (`repository`)
- **`AdjustmentDocumentRepository`** y alias en español **`RepositorioDocumentoAjuste`**:
  - `findByInvoiceId(UUID invoiceId)`
  - `findByInvoiceIdAndStatus(UUID invoiceId, AdjustmentDocumentStatus status)`
  - `findByDocumentNumber(String documentNumber)`
- **`AdjustmentDocumentItemRepository`** y alias **`RepositorioItemDocumentoAjuste`**:
  - `findByDocumentId(UUID documentId)`
  - `findByInvoiceItemId(UUID invoiceItemId)`

### 3.4. Servicios de Aplicación (`service`)
- **`CreditNoteService`** y **`ServicioNotaCredito`**:
  - `issueCreditNote(invoiceId, reason, documentNumber, items)` / `emitir(...)`:
    - Valida que la factura exista y esté en estado elegible (`CONSOLIDATED` / `PAID`, no `VOIDED` ni `DRAFT`).
    - Genera número consecutivo automático (`NC-...`) si no es provisto.
    - Soporta emisión total automática de ítems o emisión parcial detallada con validación de pertenencia del ítem a la factura.
    - Valida que el valor total del ajuste no supere el valor oficial de la factura original.
    - Aplica estado `APPLIED` y persiste con `@Transactional`.
  - `getCreditNotesByInvoice(invoiceId)`: Listado de notas crédito por factura.
  - `getCreditNoteById(id)`: Consulta de nota de crédito por UUID.

### 3.5. Controladores REST y DTOs (`web`)
- **`InvoiceController`** (`/api/v1/facturas` y `/facturas`):
  - `POST /{id}/anular`: Ejecuta la anulación registrando motivo y timestamp.
  - `POST /{id}/notas-credito`: Emite la nota crédito devolviendo HTTP 201 Created y `NotaCreditoResponseDto`.
  - `GET /{id}/notas-credito`: Lista las notas de crédito asociadas a la factura.
- **`CreditNoteController`** (`/api/v1/notas-credito` y `/notas-credito`):
  - `GET /{id}`: Obtiene el detalle completo de la nota de crédito por su ID.
- **DTOs**: `AnularFacturaRequest`, `EmitirNotaCreditoRequest`, `ItemNotaCreditoRequest`, `NotaCreditoResponseDto`, `ItemNotaCreditoResponseDto`.

### 3.6. Interfaz Web Integrada (`index.html`)
- Activación en barra lateral del módulo **Notas Crédito / Anulación** (`#view-ajustes`).
- Indicadores KPI: Facturas Activas, Facturas Anuladas, Notas Crédito Emitidas, Total Ajustado.
- Modal de anulación con captura de motivo y advertencia de liberación de índice único.
- Modal de emisión de nota crédito con selector de ajuste Total o Parcial.
- Tablas reactivas con estado de factura, conteo de notas crédito y acciones rápidas.

---

## 4. Matriz de Pruebas Automatizadas

| Suite de Pruebas | Alcance y Escenarios Validados | Resultado |
|---|---|---|
| **`AjustesYNotasCreditoIntegrationTest`** | • Anulación de factura con motivo y timestamp.<br>• Refacturación tras anulación (mismo cliente y periodo sin violar unicidad).<br>• Intento de re-anulación rechazado con `IllegalStateException`.<br>• Emisión de Nota Crédito total (cuadre exacto base + IVA 19% = total).<br>• Emisión de Nota Crédito parcial proporcional.<br>• Rechazo de nota crédito sobre factura anulada.<br>• Rechazo de nota crédito con valor superior al de la factura. | **100% PASANDO (PostgreSQL Real)** |
| **`CreditNoteControllerIntegrationTest`** | • Endpoint `GET /api/v1/notas-credito/{id}` retornando DTO correcto.<br>• Retorno de HTTP 404 para identificador inexistente. | **100% PASANDO** |
| **`InvoiceControllerIntegrationTest`** | • `POST /api/v1/facturas/{id}/anular` -> 200 OK y rechazo de re-anulación -> 400 Bad Request.<br>• `POST /api/v1/facturas/{id}/notas-credito` -> 201 Created con DTO completo.<br>• `GET /api/v1/facturas/{id}/notas-credito` -> 200 OK. | **100% PASANDO** |
| **`ArchitectureLayerRulesTest`** | Reglas ArchUnit: sin ciclos entre paquetes, capas `web -> service -> domain/repository` respetadas. | **100% PASANDO** |

---

## 5. Compuerta de Salida del Sprint 5

- [x] Flujos de anulación y notas crédito implementados y probados.
- [x] Refacturación permitida sin romper índice `uq_factura_cliente_periodo_activa`.
- [x] Consecutivos y montos financieros cuadrados al centavo con `BigDecimal` y `Money`.
- [x] Endpoints REST interoperables expuestos bajo `/api/v1` y rutas base.
- [x] Suite global de pruebas ejecutada con `./gradlew test` en verde (0 fallos).
