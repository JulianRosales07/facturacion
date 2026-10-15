# Documentación de Sprints — Corporate Billing Service

Repositorio de documentación técnica de ejecución por sprint para el microservicio `corporate-billing-service` de Sistemas Palacios.

---

## 1. Mapa de Sprints y Estado de Ejecución

| Sprint | Nombre / Enfoque | Historias de Usuario | Estado | Documento Detallado |
|:---:|---|---|:---:|---|
| **S0** | **Scaffold y Cimientos de Arquitectura** | Base técnica, ArchUnit, Flyway, Testcontainers, Actuator | **COMPLETADO** | [SPRINT_00_SCAFFOLD_Y_CIMIENTOS.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_00_SCAFFOLD_Y_CIMIENTOS.md) |
| **S1** | **Dominio + Reglas de Cálculo (sin BD)** | HU-10 a HU-16, HU-18 | **COMPLETADO** | [SPRINT_01_DOMINIO_Y_REGLAS_DE_CALCULO.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_01_DOMINIO_Y_REGLAS_DE_CALCULO.md) |
| **S2** | **Persistencia e Invariantes en BD** | HU-08, HU-09, HU-29/RF-29 | **COMPLETADO** | [SPRINT_02_PERSISTENCIA.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_02_PERSISTENCIA.md) |
| **S3** | **Integración con Sistema de Órdenes y Configuración Tributaria** | HU-01 a HU-05, HU-17, HU-25/HU-28, HU-27 | **COMPLETADO** | [SPRINT_03_INTEGRACION_ORDENES_Y_CONFIGURACION_TRIBUTARIA.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_03_INTEGRACION_ORDENES_Y_CONFIGURACION_TRIBUTARIA.md) |
| **S4** | **Generar Factura Consolidada (Slice E2E)** | HU-06, HU-07, HU-26 | **COMPLETADO** | [SPRINT_04_GENERAR_FACTURA_CONSOLIDADA.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_04_GENERAR_FACTURA_CONSOLIDADA.md) |
| **S5** | **Ajustes: Anulación y Notas Crédito** | HU-19, HU-20 | **COMPLETADO** | [SPRINT_05_AJUSTES_ANULACION_Y_NOTAS_CREDITO.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_05_AJUSTES_ANULACION_Y_NOTAS_CREDITO.md) |
| **S6** | **Exportación a Siigo y Confirmación de Lotes** | HU-21, HU-22, HU-23, HU-24 | **COMPLETADO** | [SPRINT_06_EXPORTACION_A_SIIGO_Y_CONFIRMACION.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_06_EXPORTACION_A_SIIGO_Y_CONFIRMACION.md) |
| **S7** | **Estabilización, RNF y Cierre de Producción** | RNF Seguridad, Resiliencia, Rendimiento (45 sedes), Telemetría Actuator | **COMPLETADO** | [SPRINT_07_ESTABILIZACION_Y_NO_FUNCIONALES.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_07_ESTABILIZACION_Y_NO_FUNCIONALES.md) |
| **S8** | **Autenticación y Autorización** | HU-30 a HU-37, RF-31 a RF-35, RNF-23 a RNF-25 | **COMPLETADO** | [SPRINT_08_AUTENTICACION_Y_AUTORIZACION.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_08_AUTENTICACION_Y_AUTORIZACION.md) |
| **S9** | **Realineación Tributaria y Módulo de Recaudo** | HU-50 a HU-60 | **COMPLETADO** | [SPRINT_09_REALINEACION_Y_RECAUDO.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_09_REALINEACION_Y_RECAUDO.md) |
| **S10** | **Facturación Versionada y Regeneración desde Novedades** | HU-61 a HU-66 | **COMPLETADO** | [SPRINT_10_FACTURACION_VERSIONADA.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_10_FACTURACION_VERSIONADA.md) |
| **S11** | **Exactitud del Prorrateo por Novedades y Cartera** | HU-67 a HU-72 | **COMPLETADO** | [SPRINT_11_EXACTITUD_DE_PRORRATEO_Y_CARTERA.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_11_EXACTITUD_DE_PRORRATEO_Y_CARTERA.md) |
| **S12** | **Robustecimiento de Ingesta y Deuda Técnica** | HU-73 a HU-80 | **PENDIENTE** | [SPRINT_12_ROBUSTECIMIENTO_DE_INGESTA_Y_DEUDA_TECNICA.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_12_ROBUSTECIMIENTO_DE_INGESTA_Y_DEUDA_TECNICA.md) |
| **S13** | **Corte de Cartera de Siigo como Fuente de Verdad** | HU-81 a HU-87 | **COMPLETADO** | [SPRINT_13_CORTE_DE_CARTERA_SIIGO.md](file:///c:/Users/Usuario/Documents/corporate-billing-service/corporate-billing-service/Docs/Sprints/SPRINT_13_CORTE_DE_CARTERA_SIIGO.md) |

> **Estado de la suite al cierre de S13:** `BUILD SUCCESSFUL`, 0 fallos, con las migraciones `V1` a `V11` aplicadas contra PostgreSQL 16 vía Testcontainers.

> **Nota sobre S12:** sigue pendiente y varios de sus puntos quedaron parcialmente cubiertos por S13 (idempotencia por hash y auditoría de filas, ahora resueltas para el corte de cartera pero **no** para los PDF).

---

## 1.1 Historial de migraciones Flyway

| Migración | Sprint | Contenido |
|---|:---:|---|
| `V1__esquema_inicial.sql` | S2 | 16 enums, catálogos y núcleo transaccional |
| `V2__campos_extra_factura.sql` | S4 | EAV de campos extra de factura |
| `V3__auditoria_usuario.sql` | S8 | Columnas de auditoría de usuario |
| `V4__eliminar_perfil_tributario_cliente.sql` | S9 | Elimina el perfil tributario por cliente |
| `V5__retenciones_al_pago.sql` | S9 | Mueve las retenciones al momento del pago |
| `V6__recaudo.sql` | S9 | `pago`, `pago_factura`, `retencion_pago` |
| `V7__sincronizacion_estado.sql` | S9 | `estado_sincronizacion` |
| `V8__estado_factura_reemplazada.sql` | **S10** | Solo `ALTER TYPE` del enum (aislada por restricción de PostgreSQL) |
| `V9__factura_versionada.sql` | **S10** | Versionado, índice único recreado, `factura_pdf_historico`, trazabilidad de novedades |
| `V10__tarifa_base_item_facturable.sql` | **S11** | `item_facturable.valor_mensual_base` y *backfill* |
| `V11__cartera_siigo_fuente_de_verdad.sql` | **S13** | `factura.saldo_siigo`, `cartera_snapshot`, `cartera_snapshot_linea` |

---

## 2. Compuerta de Cierre (Definition of Done)

Para considerar cualquier sprint formalmente cerrado y habilitar el siguiente, se debe validar:

1. [x] **Pruebas unitarias** en verde (sin dependencias de infraestructura).
2. [x] **Pruebas de integración** con Testcontainers / PostgreSQL real en verde.
3. [x] **ArchUnit** verificado (reglas de capas `web -> service -> domain/repository` y `repository -> domain` sin ciclos).
4. [x] Cada **Historia de Usuario** evaluada contra sus criterios de aceptación.
5. [x] **Code Review** y análisis estático sin advertencias críticas.
6. [x] Compilación y empaquetado reproducible mediante `./gradlew build`.

---

## 3. Matriz de Trazabilidad: Requerimientos vs Componentes (S0 a S11)

| Requerimiento / HU | Descripción | Componentes Principales | Pruebas de Verificación |
|---|---|---|---|
| **Arquitectura** | Capas estrictas DDD-Lite | `web`, `service`, `domain`, `repository`, `infrastructure` | `ArchitectureLayerRulesTest` |
| **Migración Inicial** | Esquema DDL Flyway completo | `db/migration/V1__esquema_inicial.sql` | `PostgresConnectionTest` |
| **HU-10** | Fecha efectiva de cobro | `BillingCalculator.resolveEffectiveBillingDate()` | `BillingCalculatorTest.testEffectiveChargeDateResolution` |
| **HU-11** | Prorrateo por días comerciales (base 30) | `ProrationCalculator.calculate()` | `ProrationCalculatorTest.testMidPeriodAlta` |
| **HU-12** | Amortización de cuotas de instalación | `InstallationFee`, `BillingCalculator` | `BillingCalculatorTest.testConsolidatedInvoiceThreeSites` |
| **HU-13** | Descuentos sobre valor bruto | `InvoiceItem.getDiscountValue()` | `BillingCalculatorTest.testItemDiscount` |
| **HU-14** | Liquidación de IVA (19%) por ítem gravado | `TaxCalculator.calculateIva()` | `TaxCalculatorTest.testIvaCalculation` |
| **HU-15** | Retenciones fiscales sobre el neto a pagar | `TaxCalculator.calculateWithholdings()` | `TaxCalculatorTest.testClientWithoutWithholding` |
| **HU-16** | Asiento de doble partida balanceado ($\sum D = \sum C$) | `AccountingEntryGenerator.generateEntry()` | `AccountingEntryGeneratorTest.testAccountingEntryGenerationAndBalance` |
| **HU-18** | Nomenclatura oficial de línea de factura Siigo | `BillingCalculator.formatItemDescription()` | `BillingCalculatorTest.testFormatItemDescription` |
| **HU-08** | Factura única activa por cliente y periodo | Índice `uq_factura_cliente_periodo_activa`, `RepositorioFactura` | `PersistenciaInvariantesIntegrationTest.testIndiceUnicoParcialRechazaFacturaDuplicada` y `testRefacturacionPermitidaTrasAnulacion` |
| **HU-09** | Snapshot fiscal inmutable en cabecera | `Invoice` (`nit_cliente`, `razon_social_cliente`, etc.) | `PersistenciaInvariantesIntegrationTest.testSnapshotFiscalInmutableNoCambiaAlEditarCliente` |
| **HU-29 / RF-29** | Conservación de histórico por inactivación | `CorporateClient`, `SubscriptionSite`, `RecordStatus` | `PersistenciaInvariantesIntegrationTest.testInactivacionConservaHistorico` |
| **HU-01** | Sincronización de suscripciones y novedades | `OrderService.syncPeriod`, `OrderSystemClient` | `OrderSyncIntegrationTest.testIdempotentSyncDoesNotDuplicate` |
| **HU-02** | Idempotencia en sincronización (*upsert*) | `OrderServiceImpl` (claves naturales por NIT y código externo) | `OrderSyncIntegrationTest.testIdempotentSyncDoesNotDuplicate`, `testSyncUpdatesExistingSiteData` |
| **HU-03** | Ingesta alternativa por archivo | `FileOrderImporter.importFromStringPayload` | `FileOrderImporterIntegrationTest.testNonBlockingFileImportWithTraceability` |
| **HU-04** | Consulta de novedades operativas | `OrderService.getUpdatesByPeriod`, `getUpdatesByPeriodAndSite` | `OrderSyncIntegrationTest.testQueryUpdatesByPeriodAndSite` |
| **HU-05** | Origen de orden y marca/zona | `OrderOrigin` (`SPE`, `MEGA_RED`, `ENLACES`), `SubscriptionSite` | `OrderSyncIntegrationTest.testSyncUpdatesExistingSiteData` |
| **HU-17** | Mapeo de cuentas contables | `AccountingAccountMapping`, `RepositorioMapeoCuentaContable` | Mapeo JPA y consultas por línea/tipo facturación |
| **HU-25 / HU-28** | Administración de configuración tributaria | `TaxConfigurationService`, `TaxConceptValidity` | `TaxConfigurationServiceIntegrationTest.testTaxConceptsAndDatedValidities`, `testCorporateClientTaxProfileOverride` |
| **HU-27** | Trazabilidad de archivos y errores no bloqueantes | `ImportedFile`, `ImportedFileLine` con JSONB | `FileOrderImporterIntegrationTest.testNonBlockingFileImportWithTraceability` |
| **Integración HTTP** | Cliente con `RestClient` configurable | `HttpOrderSystemClient` | `HttpOrderSystemClientTest` con `MockRestServiceServer` |
| **HU-06** | Generación de factura consolidada por cliente | `BillingService.generateConsolidatedInvoice` | `FacturacionConsolidadaAcceptanceTest.testGenerarFacturaConsolidadaEndToEnd` |
| **HU-07** | Liquidación de totales, IVA 19% y neto | `TaxCalculator`, `Money`, `Invoice` | `FacturacionConsolidadaAcceptanceTest.testGenerarFacturaConsolidadaEndToEnd` |
| **HU-26** | Consulta y representación gráfica HTML | `InvoiceController`, `BillingService.renderInvoiceHtml` | `InvoiceControllerIntegrationTest.testListarYDetalleFactura` |
| **HU-19** | Anulación y refacturación habilitada | `BillingService.voidInvoice`, índice parcial Postgres | `AjustesYNotasCreditoIntegrationTest`, `InvoiceControllerIntegrationTest` |
| **HU-20** | Emisión y consulta de notas crédito ligadas | `CreditNoteService.issueCreditNote`, `AdjustmentDocument` | `AjustesYNotasCreditoIntegrationTest`, `CreditNoteControllerIntegrationTest` |
| **HU-21 / HU-22** | Generación y exportación de plano Siigo F1/F20 | `SiigoFlatFileExporter`, `BatchService.getBatchFile` | `SiigoFlatFileExporterTest.testExportBatchF1BalancedLayout`, `SiigoBatchIntegrationTest` |
| **HU-23** | Confirmación de lote e ingesta de consecutivos | `BatchService.confirmBatchFromSharedFolder` | `SiigoBatchIntegrationTest.testConfirmarLoteDesdeCarpetaCompartida` |
| **HU-24** | Clasificación automática de PDFs por cliente | `SiigoPdfImporterService`, `PdfBoxMetadataExtractor` | `SiigoPdfClassifierTest`, `SiigoBatchIntegrationTest.testClasificacionAutomaticaPdfs` |
| **RNF Resiliencia** | Reintentos HTTP y tolerancia a fallos en lote | `HttpOrderSystemClient`, `BillingServiceImpl` | `HttpOrderSystemClientResilienceTest`, `BatchResilienceIntegrationTest` |
| **RNF Rendimiento** | SLA en cliente con 45 sedes (< 2000 ms) | `BillingServiceImpl`, `BillingCalculator` | `BillingPerformanceIntegrationTest` (< 400 ms en PostgreSQL) |
| **RNF Observabilidad** | Métricas de negocio en Actuator / Micrometer | `BillingMetricsPort`, `MicrometerBillingMetrics` | Contadores registrados en Actuator (`billing.invoices.generated`, etc.) |
| **HU-30 / HU-31** | Servidor de recursos OAuth2 JWT (RS256, JWKS, aud/iss/exp) | `SecurityConfig`, `AudienceAndIssuerValidator` | `SecurityEndpointsIntegrationTest.facturasWithoutTokenReturns401` |
| **HU-32 / HU-33 / HU-35** | RBAC (`ROL_ANALISTA`, `ROL_ADMINISTRADOR`, `ROL_PASARELA`, `ROL_SISTEMA`) | `JwtRoleConverter`, `@PreAuthorize` en controladores | `JwtRoleConverterTest`, `SecurityEndpointsIntegrationTest` |
| **HU-34 / RF-33** | Auditoría de usuarios en operaciones sensibles y entidades | `V3__auditoria_usuario.sql`, `CurrentUserProvider` | Persistencia en `Invoice`, `ExportBatch`, `AdjustmentDocument`, `TaxConceptValidity` |
| **HU-36 / RF-34** | Cliente Órdenes autenticado con Bearer, auto-renovación y reintento 401 | `HttpOrderSystemClient`, `OrderSystemProperties` | `HttpOrderSystemClientResilienceTest` |
| **HU-37 / RF-35** | Endpoints públicos (`/actuator/health`, `/actuator/info`) vs protegidos | `SecurityConfig` filter chain | `SecurityEndpointsIntegrationTest.actuatorHealthShouldBePublic` |
| **RNF-25** | Formato de error JSON 401/403 sanitizado sin fuga interna | `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`, `GlobalExceptionHandler` | `SecurityEndpointsIntegrationTest` |
| **HU-61** | Regeneración de factura como nueva versión | `BillingService.regenerateInvoice`, `POST /facturas/{id}/regenerar` | `InvoiceVersioningTest.testSupersedeChainsBothVersions` |
| **HU-62** | Conservación íntegra de la versión anterior | `Invoice.supersede()`, `InvoiceStatus.SUPERSEDED`, `V9__factura_versionada.sql` | `InvoiceVersioningTest.testSupersededInvoiceIsImmutable` |
| **HU-63** | Única factura vigente por cliente y periodo con todas sus sedes | Índice `uq_factura_cliente_periodo_activa` (predicado nuevo), `findCurrentByClientAndPeriod` | `PersistenciaInvariantesIntegrationTest`, `FacturacionConsolidadaAcceptanceTest` |
| **HU-64** | Guarda fiscal ante CUFE transmitido y factura pagada | `FacturaNoRegenerableException`, `Invoice.canBeRegenerated()` | `InvoiceVersioningTest.testPaidInvoiceCannotBeRegenerated`, `testVoidedInvoiceCannotBeRegenerated` |
| **HU-65** | Histórico del PDF oficial e ingesta idempotente | `InvoicePdfHistory`, `SiigoPdfImporterService.linkPdfToInvoice` | `SiigoBatchIntegrationTest`, `SiigoPdfClassifierTest` |
| **HU-66** | Trazabilidad novedad → versión de factura | `novedad_orden.factura_id`, `OrderUpdate.markAsBilled()`, `releaseByInvoiceId` | `InvoiceVersioningTest.testOrderUpdateBillingTraceability` |
| **HU-67** | Efecto económico real de BAJA y SUSPENSION | `BillingCalculator.calculateSiteItems`, `ProrationCalculator.calculateSegment` | `BillingCalculatorNoveltyTest.testBajaChargesOnlyConsumedDays` |
| **HU-68** | Cambio de plan partido con tarifa vieja y nueva | `BillingCalculator` (`RateSegment`), `resolveOpeningRates` | `BillingCalculatorNoveltyTest.testPlanUpgradeSplitsMonthWithBothRates`, `testPlanDowngradeSplitsMonthWithBothRates` |
| **HU-69** | Tarifa mensual base persistida por línea | `item_facturable.valor_mensual_base`, `InvoiceItem.baseMonthlyRate` | `BillingCalculatorNoveltyTest.testFullMonthBaseline` |
| **HU-70** | Sede dada de baja dentro del periodo sigue facturando lo consumido | `BillingServiceImpl.loadBillableSites`, `BillingCalculator.isBillableInPeriod` | `BillingCalculatorNoveltyTest.testDeactivatedSiteStillBillsConsumedSegment` |
| **HU-71** | Saldo pendiente real en cartera tras el pago | `PaymentServiceImpl.enrichInvoiceBalances` y cruce en `register` | `PaymentServiceIntegrationTest.testRegisterPartialPaymentAndVersionImmutability` |
| **HU-72** | Severidad diferenciada del ciclo de sincronización | `ScheduledSiigoSyncJob`, `SyncStatusEntity.fail(...)` | `ScheduledSiigoSyncJobTest.testFaultToleranceOnPdfFailure` |
| **HU-81** | Lectura del corte de cartera `.xlsx` de la carpeta compartida | `SiigoCarteraReader`, `PoiXlsxCarteraReader`, `listCarteraSpreadsheets()` | `PoiXlsxCarteraReaderTest` (9 casos) |
| **HU-82** | Asignación determinista de la factura al cliente corporativo | `CarteraSyncService` (índice por comprobante + consecutivo, validación de NIT) | `PoiXlsxCarteraReaderTest.testVoucherCodeMapsToBillingType` |
| **HU-83** | Saldo de Siigo como fuente de verdad de la cartera | `Invoice.getPendingBalance()`, `applySiigoBalance()`, `factura.saldo_siigo` | `PaymentServiceIntegrationTest` (regresión del saldo local) |
| **HU-84** | Protección contra cortes obsoletos | `Invoice.applySiigoBalance()`, `saldo_siigo_corte_en` | `PoiXlsxCarteraReaderTest.testCutoffDateIsMostRecentDocumentDate` |
| **HU-85** | Conciliación Siigo vs saldo calculado | `CarteraSyncService.getLatestReconciliation`, `GET /cartera/conciliacion` | Lectura pura sobre `cartera_snapshot_linea.diferencia` |
| **HU-86** | Auditoría de filas del corte no aplicadas | `CarteraSnapshotLine`, `GET /cartera/pendientes-de-resolver` | `PoiXlsxCarteraReaderTest.testFormatErrorDoesNotAbortRead` |
| **HU-87** | Política explícita ante facturas ausentes del corte | `siigo.cartera.tratar-ausencia-como-saldo-cero`, `cartera_snapshot.facturas_sin_corte` | Valor conservador por defecto |

