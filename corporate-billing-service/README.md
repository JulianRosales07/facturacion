# Corporate Billing Service (`corporate-billing-service`)

> **Consumo y consulta de facturas corporativas ya emitidas por Siigo desde una carpeta compartida del servidor.**  
> *Sistemas Palacios — Soluciones Corporativas de Telecomunicaciones.*

---

## 1. Propósito y alcance vigente

El servicio recibe **PDF y archivos de datos** que Siigo publica en una carpeta del servidor de la empresa. Incorpora las facturas a la base de datos, conserva copias de los PDF y permite consultarlas sin duplicar documentos. Soporta varias facturas de un mismo NIT en un mes y lectura automática cada hora o manual bajo demanda.

La gestión de pagos y la pasarela **no pertenecen a este alcance**, confirmado el 28 de septiembre de 2026. Los módulos anteriores de generación, órdenes, tributos y recaudo permanecen en el código; las secciones históricas de este README no amplían el alcance actual.

**Configuración, formatos compatibles, rutas y pendientes de acceso:** [Guía de consumo desde el servidor](Docs/CONSUMO_FACTURAS_SERVIDOR.md).

**Panel, autenticación corporativa y nuevo historial:** [Guía del frontend de recepción](Docs/FRONTEND_RECEPCION.md).

El lector inicial está validado contra el movimiento contable Excel incluido en el proyecto (1.209 facturas F1/F20), además de aceptar JSON normalizado. La conexión real requiere la ruta, permisos y confirmación del formato definitivo que publique la empresa.

---

## 2. Stack Tecnológico

| Componente | Tecnología | Versión / Detalle |
| :--- | :--- | :--- |
| **Lenguaje** | Java | **21 LTS** (Toolchain configurado) |
| **Framework** | Spring Boot | **4.1.1** (Web MVC, Data JPA, Validation, Actuator) |
| **Base de Datos** | PostgreSQL | **13+** (Soporte nativo para UUIDs con `pgcrypto` y JSONB) |
| **Migraciones** | Flyway | Migraciones SQL versionadas (`V1__esquema_inicial.sql`) |
| **Pruebas** | JUnit 5, Mockito, Testcontainers | Pruebas unitarias de dominio y de persistencia con PostgreSQL real |
| **Arquitectura** | ArchUnit | Reglas de arquitectura aplicadas automáticamente en cada build |
| **Build Tool** | Gradle | Groovy DSL (`gradlew` / `gradlew.bat`) |

---

## 3. Convención de Nombres (Obligatoria y Bilingüe)

Para preservar la pureza técnica, estándares de la industria y la normativa contable/fiscal colombiana:

1. **Inglés (Todo el Código Java):**
   * Nombre del repositorio y artefacto: `corporate-billing-service`.
   * Paquete base y capas: `com.sistemaspalacios.billing`, `web`, `controller`, `dto`, `mapper`, `service`, `domain`, `model`, `vo`, `repository`, `infrastructure`, `config`.
   * **Todas las clases, interfaces, records, enums, métodos y variables**: `Invoice`, `BillableItem`, `CorporateClient`, `BranchSubscription`, `OrderNovelty`, `BillingPeriod`, `InstallationFee`, `ExportBatch`, `AdjustmentDocument`, `Money`, `TaxProfile`, `BillingService`, `OrderService`, `InvoiceRepository`, `calculateProration(...)`, `TaxCalculator`, etc.
2. **Español (Base de Datos & Normativa Fiscal):**
   * Tablas y columnas de base de datos en PostgreSQL: `factura`, `item_facturable`, `cliente_corporativo`, `sede_suscripcion`, `novedad_orden`, `cuota_instalacion`, `lote_exportacion`, `mapeo_cuenta_contable`.
   * Mapeadas explícitamente en JPA: `@Table(name = "factura")`, `@Column(name = "valor_mensual")`.
   * Términos fiscales y regulatorios: `nit`, `iva`, `retefuente`, `reteica`, `reteiva`, `cufe`, `F1`, `F20`, `prorrateo`.
   * Narrativa, comentarios y documentación: español.

---

## 4. Arquitectura en Capas (Layered / DDD-Lite)

El microservicio implementa **Domain-Driven Design Lite (DDD-Lite)** sobre una arquitectura en capas estrictamente verificada por **ArchUnit**:

```
com.sistemaspalacios.billing
├── web/                  # Controladores REST, DTOs de entrada/salida, mappers y @RestControllerAdvice
│   ├── controller/       # InvoiceController, OrderController, ExportBatchController, etc.
│   ├── dto/              # Request / Response DTOs (GenerateInvoiceRequest, InvoiceResponse, etc.)
│   ├── mapper/           # Mapeo DTO ↔ Dominio (MapStruct o a mano)
│   └── error/            # GlobalExceptionHandler y respuestas estandarizadas RFC 7807
├── service/              # Servicios de aplicación: orquestación de flujos y demarcación @Transactional
│                         # BillingService, OrderService, ExportBatchService, CreditNoteService
├── domain/               # LÓGICA DE NEGOCIO PURA (independiente de Spring y frameworks)
│   ├── model/            # Entidades JPA con comportamiento (Invoice, CorporateClient, BranchSubscription)
│   ├── vo/               # Value Objects inmutables (Money, TaxProfile, ProrationResult, DateRange)
│   └── service/          # Servicios de cálculo puro (BillingCalculator, TaxCalculator, AccountingEntryGenerator)
├── repository/           # Interfaces Spring Data JPA (InvoiceRepository, CorporateClientRepository, etc.)
├── infrastructure/       # Clientes HTTP externos (OrderSystemClient), exportadores Siigo (SiigoFlatFileExporter)
└── config/               # Beans de infraestructura, Jackson, seguridad y propiedades
```

### Regla de Oro Arquitectónica:
$$\text{web} \longrightarrow \text{service} \longrightarrow \text{domain} \longleftarrow \text{repository}$$
* Ninguna capa inferior depende de `web` ni de `service`.
* La lógica de cálculo financiero no reside en los controladores ni en los servicios de aplicación: vive en `domain/service` para garantizar cobertura unitaria del 100% sin levantar contextos pesados.

---

## 5. Mapa de Épicas e Historias de Usuario

El proyecto se compone de **8 Épicas** y **27 Historias de Usuario** detalladas en [HISTORIAS_Y_REQUERIMIENTOS_corporate-billing-service.md](file:///c:/Users/Usuario/Downloads/corporate-billing-service/corporate-billing-service/Docs/Prompts/HISTORIAS_Y_REQUERIMIENTOS_corporate-billing-service.md):

* **E1 — Sincronización con Órdenes:** HU-01 a HU-05 (Upsert idempotente, tolerancia a fallos, marcas).
* **E2 — Generación de Factura Consolidada:** HU-06 a HU-09 (Agrupación por NIT, unicidad activa, snapshot inmutable).
* **E3 — Reglas de Cálculo:** HU-10 a HU-15 (Fecha de certificación, prorrateo, cuotas, IVA, retenciones).
* **E4 — Asiento Contable de Doble Partida:** HU-16 a HU-18 (Balance estricto débitos=créditos, cuentas configurables).
* **E5 — Ajustes:** HU-19 y HU-20 (Anulación, refacturación y notas crédito ligadas).
* **E6 — Exportación a Siigo y Confirmación:** HU-21 a HU-24 (Archivo plano lote F1/F20, CUFE y consecutivos).
* **E7 — Configuración y Catálogos:** HU-25 (Marcas, zonas, novedades, perfiles tributarios).
* **E8 — Trazabilidad y Diagnóstico:** HU-26 y HU-27 (Consulta de facturas y registros crudos de importación).

---

## 6. Plan de Desarrollo en Sprints

El desarrollo está estructurado en **7 Sprints incrementales** (~8 semanas en total). 

> ⛔ **Regla Estricta de Calidad:** Cada sprint cuenta con un **Quality Gate obligatorio**. No se avanza al siguiente sprint hasta que el **100% de las pruebas del sprint actual estén en verde (`BUILD SUCCESSFUL`)**. Si una sola prueba falla, se detiene el avance.

| Sprint | Duración | Enfoque / Entregable Principal | Historias Cubiertas | Quality Gate de Salida (Pruebas) |
| :---: | :---: | :--- | :--- | :--- |
| **Sprint 0** | **3 a 4 días** | **Scaffold, Arquitectura Base & Calidad** | *Cimientos técnicos* | Reglas ArchUnit en verde + `Dinero` testeado al 100%. |
| **Sprint 1** | **1 semana** | **Motor de Dominio & Cálculo Financiero** | HU-10 al HU-15 (E3) | Suite de pruebas unitarias puras (TDD) en verde sin errores de redondeo. |
| **Sprint 2** | **1 semana** | **Persistencia Relacional & Catálogos** | HU-08, HU-09, HU-25 (E2/E7) | Migración Flyway `V1` + Índice único de factura activa probado en Postgres real. |
| **Sprint 3** | **1.5 semanas** | **Ingesta & Sincronización con Órdenes** | HU-01 al HU-05, HU-27 (E1/E8) | Tests WireMock OK + *Upsert* idempotente verificado sin duplicados. |
| **Sprint 4** | **1.5 semanas** | **Factura Consolidada & API REST** | HU-06, HU-07, HU-26 (E2/E8) | Slice E2E MockMvc OK + Totales de cabecera e ítems cuadrados al centavo. |
| **Sprint 5** | **1.5 semanas** | **Asiento Contable & Exportador Siigo** | HU-16 al HU-18, HU-21 al HU-24 (E4/E6) | Validador estricto de doble partida ($\sum D = \sum C$) y lote plano verificado. |
| **Sprint 6** | **1 semana** | **Ajustes (Notas Crédito) & Hardening** | HU-19, HU-20 (E5) | Ciclo de anulación-refacturación probado + Suite global de pruebas al 100%. |

👉 **Consulta el plan exhaustivo con tareas técnicas, dependencias, horas estimadas y DoD en:**  
[Docs/PLAN_DE_DESARROLLO_SPRINTS.md](file:///c:/Users/Usuario/Downloads/corporate-billing-service/corporate-billing-service/Docs/PLAN_DE_DESARROLLO_SPRINTS.md)

---

## 7. Instrucciones de Construcción y Ejecución

### Requisitos Previos:
* **Java 21 JDK** instalado y configurado en `JAVA_HOME`.
* **Docker** en ejecución (necesario para levantar contenedores de PostgreSQL en las pruebas con Testcontainers).

### Comandos de Compilación y Pruebas:
```bash
# Compilar el proyecto y verificar sintaxis
./gradlew build -x test

# Ejecutar la suite completa de pruebas (Unitarias + ArchUnit + Testcontainers)
./gradlew test

# En Windows PowerShell:
.\gradlew.bat test
```

### Ejecución Local:
```bash
./gradlew bootRun
```

---

## 8. Documentación de Referencia

* [Historias de Usuario y Requerimientos](file:///c:/Users/Usuario/Downloads/corporate-billing-service/corporate-billing-service/Docs/Prompts/HISTORIAS_Y_REQUERIMIENTOS_corporate-billing-service.md)
* [Especificación y Prompt de Construcción](file:///c:/Users/Usuario/Downloads/corporate-billing-service/corporate-billing-service/Docs/Prompts/PROMPT_corporate-billing-service.md)
* [Plan de Desarrollo por Sprints](file:///c:/Users/Usuario/Downloads/corporate-billing-service/corporate-billing-service/Docs/PLAN_DE_DESARROLLO_SPRINTS.md)
* [Directrices para Agentes de IA (`AGENTS.md`)](file:///c:/Users/Usuario/Downloads/corporate-billing-service/corporate-billing-service/AGENTS.md)
