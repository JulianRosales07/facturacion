# Sprint 4 — Generar Factura Consolidada (Slice Canónico E2E)

- **Servicio:** `corporate-billing-service`
- **Paquetes:** `com.sistemaspalacios.billing.domain.model`, `com.sistemaspalacios.billing.domain.service`, `com.sistemaspalacios.billing.repository`, `com.sistemaspalacios.billing.service`, `com.sistemaspalacios.billing.web`
- **Historias de Usuario Cubiertas:** HU-06 (Generación de Factura Consolidada por Cliente), HU-07 (Liquidación de Totales, IVA 19% y Retenciones / Neto a Pagar), HU-26 (Consulta y Representación Gráfica vía REST).
- **Estado:** Completado y Verificado en Verde (100% de pruebas de aceptación end-to-end, pruebas de integración con PostgreSQL real y reglas de arquitectura ArchUnit pasando).

---

## 1. Objetivo del Sprint

Implementar el flujo nuclear de negocio de punta a punta (el *slice canónico* del MVP) que sustituye definitivamente la liquidación manual en hojas de cálculo de Excel:
1. **HU-06: Generación de Factura Consolidada por Cliente:** Orquestar en un único proceso los maestros operativos sincronizados (sedes activas, novedades de corte/alta, cuotas de instalación pendientes), invocando los servicios puros de dominio (`BillingCalculator`, `ProrationCalculator`, `TaxCalculator`) para consolidar en una única factura por NIT todas las sedes y servicios suscritos.
2. **HU-07: Liquidación de Totales, IVA y Neto a Pagar:** Calcular el valor bruto, descuentos, subtotal gravable, IVA general del 19% y el valor total oficial de la factura (base + IVA). En paralelo, resolver las retenciones fiscales (retefuente, reteica, reteiva, estampillas) basadas en el perfil tributario y vigencias vigentes para obtener el `neto_a_pagar`, preservando intacto el valor oficial de la factura.
3. **HU-26: Consulta y Representación Gráfica vía API REST:** Exponer endpoints REST interoperables (`/api/v1/facturas`) para generar, listar, consultar el detalle estructurado de facturas y obtener la representación gráfica HTML oficial (fiel al layout de Siigo / sistemas contables de Sistemas Palacios).

---

## 2. Historias de Usuario Implementadas y Criterios de Aceptación

| ID | Historia de Usuario | Mecanismo de Garantía | Criterio de Aceptación Cumplido |
|---|---|---|---|
| **HU-06** | **Generación de Factura Consolidada** | `BillingService.generateConsolidatedInvoice` / `generateConsolidatedInvoices`, `Invoice`, `InvoiceItem` | Para un periodo y tipo de facturación (F1/F20), agrupa todas las sedes activas del cliente corporativo en una sola factura. Integra cargos recurrentes, prorrateos por días de servicio y cuotas de instalación vigentes. Respeta el índice de unicidad parcial de PostgreSQL `uq_factura_cliente_periodo_activa`. |
| **HU-07** | **Totales Financieros, IVA 19% y Retenciones** | `BillingCalculator`, `TaxCalculator`, `Money` (`BigDecimal` con `RoundingMode.HALF_UP`) | Suma exacta de ítems = subtotal; liquidación de IVA del 19% sobre ítems gravados; `valor_total = subtotal + IVA`. Las retenciones se calculan sobre el neto sin alterar el valor oficial ni el futuro asiento contable (`neto_a_pagar = valor_total - retenciones`). |
| **HU-26** | **Consulta y Representación Gráfica** | `InvoiceController`, `BillingService.renderInvoiceHtml`, DTOs de respuesta | Endpoints `GET /facturas`, `GET /facturas/{id}` y `GET /facturas/{id}/representacion-grafica`. Retorna el detalle jerárquico de sedes e ítems, o el documento HTML formateado con razón social, NIT, desglose tributario, datos del emisor y pie de imprenta fiscal. |

---

## 3. Arquitectura y Componentes Implementados

```
  HTTP REST Request
  POST /api/v1/facturas/generar
             │
             ▼
   [InvoiceController] ──────────► Valida DTO (GenerarFacturaRequest)
             │
             ▼
      [BillingService] (ServicioFacturacion)
             │
             ├─► [RepositorioCliente / RepositorioSede] ── Carga sedes activas
             ├─► [RepositorioNovedad] ─────────────────── Evalúa altas/prorrateos
             ├─► [RepositorioCuotaInstalacion] ────────── Amortiza cuotas pendientes
             ├─► [TaxConfigurationService] ───────────── Resuelve perfil y vigencias
             │
             ▼
    [Servicios de Dominio]
    • ProrationCalculator ── Días comerciales (base 30)
    • BillingCalculator   ── Construye InvoiceItems con formato SEDE.INT.FIB...
    • TaxCalculator       ── IVA 19% + Retenciones sobre neto
             │
             ▼
       [Invoice Entity] ── Invariante uq_factura_cliente_periodo_activa
             │
             ▼
   [RepositorioFactura] ── Persistencia atómica en PostgreSQL con @Transactional
```

### 3.1. Dominio y Reglas de Cálculo (`domain.service`)
- **`BillingCalculator`**: Orquesta el cálculo integral del ítem facturable.
  - Fecha efectiva de cobro: fecha de certificación salvo observación explícita.
  - Nomenclatura oficial: genera la descripción estándar de línea para Siigo `SEDE.INT.FIB.<TIPO>.<VELOCIDAD> GR <DD-DD MMM AAAA>`.
  - Cuotas de instalación: genera ítem con sufijo `(cuota_actual/total_cuotas)` y actualiza el contador de cuotas facturadas (`billedInstallments`).
- **`ProrationCalculator`**: Aplica cálculo por días comerciales (base 30) para altas o modificaciones a mitad de periodo, respetando la excepción de días completos si existe novedad de regularización.
- **`TaxCalculator`**:
  - Calcula el IVA general (19%) sobre ítems gravados.
  - Calcula retenciones (retefuente, reteica, reteiva, estampillas) aplicando base mínima y porcentaje vigente según la fecha de corte y la clasificación técnica del servicio (`INTERNET` vs `SOFTWARE`).
- **`Invoice` / `InvoiceItem`**:
  - Raíz de agregado `Invoice` (`factura`) que encapsula la lista inmutable de ítems facturables, snapshots fiscales inmutables (`clientNit`, `clientBusinessName`, `clientFiscalAddress`, `clientBillingEmail`) y totales monetarios (`Money`).

### 3.2. Servicios de Aplicación (`service`)
- **`BillingService`** e interfaz en español **`ServicioFacturacion`**:
  - `generateConsolidatedInvoices(periodCode, billingType, cutType, optionalNit)`: Genera facturas en lote para todos los clientes corporativos activos o para un cliente específico.
  - `generateConsolidatedInvoice(periodCode, billingType, cutType, nit)`: Generación individual que valida existencia previa de factura activa; si ya existe una factura en estado no anulado para el cliente y periodo, lanza `FacturaActivaYaExisteException`.
  - `getInvoices(periodCode, status)`: Consulta paginada/filtrada de facturas.
  - `getInvoiceById(id)`: Retorna la factura con sus ítems cargados.
  - `renderInvoiceHtml(invoiceId)`: Renderiza el documento comercial oficial en HTML con encabezado de Sistemas Palacios, datos del corporativo, tabla de ítems discriminados por sede, resumen de IVA y resolución DIAN.

### 3.3. Controladores REST, DTOs y Manejo de Errores (`web`)
- **`InvoiceController`** (mapeado bajo `/api/v1/facturas` y `/facturas`):
  - `POST /generar`: Inicia la generación consolidada. Responde HTTP 200 con `GenerarFacturasResponse`.
  - `GET /`: Lista facturas con filtros opcionales. Responde `List<FacturaResponseDto>`.
  - `GET /{id}`: Retorna el detalle completo de la factura con sus líneas (`FacturaDetalleResponseDto`).
  - `GET /{id}/representacion-grafica`: Retorna el HTML listo para visualización o impresión en navegador (`text/html;charset=UTF-8`).
- **`GlobalExceptionHandler`**:
  - Intercepta `FacturaActivaYaExisteException` y devuelve HTTP 409 Conflict con payload estructurado:
    ```json
    {
      "codigo": "FACTURA_ACTIVA_YA_EXISTE",
      "mensaje": "Ya existe una factura activa para el cliente y periodo seleccionados"
    }
    ```
- **DTOs Implementados**:
  - `GenerarFacturaRequest`: Contiene `periodo`, `tipoFacturacion` (F1/F20), `tipoCorte` y `nit` (opcional).
  - `GenerarFacturasResponse`: Resumen con total de facturas generadas y lista de IDs/números.
  - `FacturaResponseDto`, `FacturaDetalleResponseDto`, `ItemFacturaResponseDto`.

---

## 4. Matriz de Pruebas Automatizadas

| Suite de Pruebas | Escenario y Casos de Borde Validados | Resultado |
|---|---|---|
| **`FacturacionConsolidadaAcceptanceTest`** | **Prueba de Aceptación End-to-End Oficial:**<br>• Cliente corporativo con 3 sedes activas.<br>• Sede 1: mes completo ($2,722,500).<br>• Sede 2: alta a mitad de periodo con prorrateo comercial de 16 días ($483,999.70).<br>• Sede 3: mes completo ($660,000) + cuota de instalación pendiente 1 de 3 ($247,500).<br>• Verificación de 4 ítems generados, descripción Siigo correcta, totales exactos (Subtotal $4,113,999.70 + IVA 19% $781,659.94 = Total $4,895,659.64).<br>• Verificación de actualización de cuota a estado `BILLING`.<br>• Verificación de representación gráfica HTML con NIT, razón social y datos fiscales.<br>• Verificación de rechazo con `FacturaActivaYaExisteException` al reintentar generar sobre el mismo periodo. | **100% PASANDO (PostgreSQL Real)** |
| **`InvoiceControllerIntegrationTest`** | • `POST /api/v1/facturas/generar` genera factura exitosamente (HTTP 200).<br>• Reintento de generación inmediata retorna HTTP 409 Conflict (`FACTURA_ACTIVA_YA_EXISTE`).<br>• `GET /api/v1/facturas` y `GET /api/v1/facturas/{id}` devuelven contratos JSON exactos.<br>• `GET /api/v1/facturas/{id}/representacion-grafica` retorna HTML válido con HTTP 200. | **100% PASANDO** |
| **`ArchitectureLayerRulesTest`** | Cumplimiento estricto de capas ArchUnit: `web -> service -> domain/repository` sin dependencias cíclicas. | **100% PASANDO** |

---

## 5. Compuerta de Salida del Sprint 4

- [x] Generación consolidada por NIT que consolida sedes, prorrateos y cuotas en una única factura.
- [x] Respeto estricto del índice de unicidad parcial en base de datos (`uq_factura_cliente_periodo_activa`).
- [x] Liquidación financiera exacta con `Money` / `BigDecimal` (base + IVA 19% = total oficial; retenciones sobre neto a pagar).
- [x] Amortización atómica de cuotas de instalación al emitir factura.
- [x] Endpoints REST `/api/v1/facturas` operativos con DTOs, códigos HTTP estándar y control de excepciones (409 Conflict).
- [x] Representación gráfica HTML corporativa generable y consultable vía API.
