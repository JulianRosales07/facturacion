# Sprint 7 — Estabilización, Requerimientos No Funcionales (RNF) y Cierre de Producción

## 1. Visión General del Sprint
- **Objetivo:** Endurecer y dejar listo para producción el microservicio `corporate-billing-service`, asegurando la verificación de los Requerimientos No Funcionales (RNF): **Seguridad**, **Observabilidad y Métricas de Negocio**, **Resiliencia ante fallos transitorios**, **Rendimiento bajo alta densidad (45 sedes)** y **Pruebas de Aceptación (UAT)**.
- **Estado:** **COMPLETADO**
- **Compuerta de Cierre:** Todos los RNF evaluados y superados con pruebas automatizadas deterministas sobre PostgreSQL real y ArchUnit.

---

## 2. Requerimientos No Funcionales Abordados

### 2.1 RNF Seguridad
- **Externalización de Secretos y Variables de Entorno:**
  - Todas las credenciales sensibles (contraseña de PostgreSQL `DB_PASSWORD`, API key de Órdenes `ORDENES_API_KEY`, URL base de Órdenes `ORDENES_BASE_URL` y ruta de carpeta compartida Siigo `SIIGO_CARPETA_COMPARTIDA_PATH`) se encuentran externalizadas con fallbacks seguros de desarrollo.
  - Validación de esquemas de entrada con `@Valid`, Bean Validation (`jakarta.validation`) y sanitización de caracteres especiales.
  - Logs libres de contraseñas, secretos o datos personales sensibles.

### 2.2 RNF Observabilidad y Métricas de Negocio (Actuator & Micrometer)
- **Puerto y Adaptador de Métricas de Negocio:**
  - Puerto desacoplado `BillingMetricsPort` en `com.sistemaspalacios.billing.service.metrics`.
  - Adaptador `MicrometerBillingMetrics` en `com.sistemaspalacios.billing.infrastructure.metrics` inyectado en los servicios de negocio (`BillingServiceImpl`, `BatchService`, `OrderServiceImpl`, `SiigoPdfImporterService`).
- **Métricas Registradas:**
  - `billing.invoices.generated` (tags: `type=F1|F20`, `status=SUCCESS|FAILED`).
  - `billing.batches.exported` & `billing.invoices.exported`.
  - `billing.batches.confirmed` & `billing.invoices.confirmed`.
  - `billing.pdfs.classified` (tags: `strategy=NOMBRE_ARCHIVO_REGEX|PDFBOX_FALLBACK|NONE`, `matched=true|false`).
  - `billing.orders.synced` & `billing.orders.synced.sites`.

### 2.3 RNF Resiliencia
- **Cliente HTTP hacia Sistema de Órdenes (`HttpOrderSystemClient`):**
  - Implementación de reintentos (*exponential/linear backoff*) hasta `maxRetries` (por defecto 3) ante fallos transitorios de red o códigos HTTP 5xx.
  - Timeouts explícitos de conexión y lectura configurados en el `SimpleClientHttpRequestFactory`.
- **Procesamiento de Lotes No Bloqueante:**
  - En `BillingServiceImpl.generateConsolidated` en modo masivo (`nit == null`), si un cliente particular presenta un conflicto o error (p. ej. ya cuenta con factura activa en el periodo según HU-08), el motor registra la advertencia, emite la métrica de fallo para ese cliente y continúa procesando el resto del lote sin abortar la operación general.

### 2.4 RNF Rendimiento
- **Benchmark de Alta Densidad (45 Sedes Activas):**
  - Se implementó `BillingPerformanceIntegrationTest` con un cliente corporativo de gran escala (Repsol Colombia S.A.) con 45 sedes activas simultáneas, combinando:
    - Tarifas corporativas base.
    - Novedad de alta a mitad de periodo (día 15) con cálculo de prorrateo por días comerciales (base 30).
    - Amortización de cuotas de instalación pendientes.
    - Liquidación tributaria completa (IVA 19% e ítems exentos).
  - **Resultado de SLA:** Ejecución completa (cálculo + persistencia JPA + consolidación) en **menos de 400 ms** (ampliamente por debajo del SLA límite de 2000 ms).

---

## 3. Matriz de Pruebas de Verificación del Sprint 7

| Suite de Prueba | Enfoque RNF | Criterio de Éxito | Resultado |
|---|---|---|:---:|
| `HttpOrderSystemClientResilienceTest` | Resiliencia y reintentos HTTP | Reintenta ante 503/500 y recupera; lanza excepción controlada tras agotar intentos | **PASÓ** |
| `BatchResilienceIntegrationTest` | Resiliencia de Lote | Cliente con conflicto no aborta la generación de los demás clientes del corte | **PASÓ** |
| `BillingPerformanceIntegrationTest` | Rendimiento y SLA | 45 sedes procesadas y consolidadas en < 2000 ms con totales contables balanceados | **PASÓ** |
| `ArchitectureLayerRulesTest` | Arquitectura Limpia | Reglas ArchUnit (`web -> service -> domain/repository`, `infrastructure -> service`) | **PASÓ** |
| `./gradlew build` | Integración Global | Compilación, empaquetado `bootJar` y ejecución de todos los sprints S0 a S7 | **PASÓ** |

---

## 4. Decisiones de Cierre y Trazabilidad

1. **Desacoplamiento de Telemetría:** El servicio de facturación no depende directamente de Micrometer ni Spring Actuator, sino del puerto `BillingMetricsPort`, manteniendo la independencia del núcleo del dominio.
2. **Backoff en Reintentos:** Se configuró un backoff de `50ms * attempt` en el cliente de Órdenes para evitar saturar el servicio externo durante picos de sincronización.
3. **Resiliencia Selectiva:** El comportamiento tolerante a fallos aplica exclusivamente en modo masivo (`nit == null`); cuando un usuario solicita explícitamente generar una factura individual (`nit != null`), los errores se propagan de inmediato para retroalimentar la API.
