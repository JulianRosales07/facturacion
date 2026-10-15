# Sprint 0 — Scaffold y Cimientos de Arquitectura

- **Servicio:** `corporate-billing-service`
- **Paquete Base:** `com.sistemaspalacios.billing`
- **Stack Técnico:** Java 21, Spring Boot 4.1.1, Gradle (Groovy DSL), Spring Data JPA, PostgreSQL, Flyway, Lombok, Actuator, ArchUnit, Testcontainers.
- **Estado:** Completado y Verificado en Verde.

---

## 1. Objetivo del Sprint

Establecer las bases arquitectónicas, infraestructura de configuración, soporte de persistencia relacional con Flyway, perfiles por entorno y compuertas de calidad automáticas (ArchUnit y Testcontainers) para permitir la construcción desacoplada de los módulos de dominio y servicios del sistema.

---

## 2. Tareas Realizadas y Componentes Construidos

### 2.1. Estructura de Capas (DDD-Lite / Layered Architecture)
Se implementó la separación estricta de paquetes bajo `com.sistemaspalacios.billing`:
```
com.sistemaspalacios.billing
├── web/            # Controladores REST, Request/Response DTOs, Exception Handlers
├── service/        # Servicios de aplicación y orquestación transaccional (@Transactional)
├── domain/         # Núcleo de negocio sin dependencias externas
│   ├── model/      # Entidades de dominio con comportamiento rico
│   ├── vo/         # Value Objects inmutables (Money, DateRange, TaxProfile, etc.)
│   └── service/    # Servicios de dominio puros (Calculadoras y balanceador contable)
├── repository/     # Interfaces Spring Data JPA y consultas especializadas
├── infrastructure/ # Adaptadores externos (clientes HTTP, importadores de archivos, Siigo)
└── config/         # Beans de configuración, seguridad y propiedades
```

### 2.2. Dependencias y Herramientas (`build.gradle`)
Se configuraron las dependencias del ecosistema de Spring Boot y las herramientas de calidad:
- **Core:** Spring Boot Starter Web, Spring Boot Starter Validation, Spring Boot Starter Actuator.
- **Persistencia:** Spring Boot Starter Data JPA, PostgreSQL Driver (`org.postgresql:postgresql`), Flyway Core (`org.flywaydb:flyway-core`, `flyway-database-postgresql`).
- **Productividad:** Project Lombok (anotaciones de boilerplate).
- **Pruebas y Validación Arquitectural:**
  - `org.springframework.boot:spring-boot-starter-test` (JUnit 5, Mockito, AssertJ).
  - `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql` para bases de datos reales en Docker durante integración.
  - `com.tngtech.archunit:archunit-junit5:1.3.0` para verificación formal de dependencias entre capas.

### 2.3. Perfiles y Propiedades de Entorno (`application.yml`)
Se definieron configuraciones separadas por perfil:
- **Actuator (`/actuator/health`, `/actuator/metrics`, `/actuator/info`):** Expuesto para observabilidad y liveness/readiness probes.
- **Perfil `local`:** H2 in-memory emulando PostgreSQL (`MODE=PostgreSQL`) para pruebas rápidas de desarrollo sin Docker.
- **Perfil `postgres` (Producción / Staging):** Conexión vía variables de entorno a PostgreSQL 16+ con Flyway activo para ejecución automática de migraciones.

### 2.4. Migración Flyway Inicial (`V1__esquema_inicial.sql`)
Se creó el esquema DDL completo cubriendo todas las tablas requeridas por la especificación:
1. `cliente_corporativo`: Maestro de clientes con clasificación tributaria (Gran Contribuyente, Autorretenedor), municipio ICA y régimen.
2. `sede_suscripcion`: Sedes operativas asociadas a clientes, código NIC, orden de origen y canon base.
3. `periodo_facturacion`: Periodos mensuales (ej. `2026-09`) con tipo de corte (`ADELANTADO` o `VENCIDO`).
4. `novedad_orden`: Registro de altas, bajas, suspensiones, reconexiones o cambios de plan con fecha efectiva y observaciones.
5. `cuota_instalacion`: Control de amortización de costos de acometida/instalación (valor total, cuotas pactadas, cuotas facturadas).
6. `factura`: Cabecera consolidada por cliente y periodo, estado (`BORRADOR`, `EMITIDA`, `ANULADA`), totales y snapshot fiscal inmutable del cliente al corte.
7. `item_factura`: Detalle de líneas facturadas por sede/concepto con base gravable, descuento e IVA.
8. `concepto_tributario` y `vigencia_concepto`: Tablas maestras para configuración de impuestos con vigencias fechadas (ReteFuente, ReteICA, ReteIVA, Estampillas).
9. `mapeo_cuenta_contable`: Catálogo de parametrización de cuentas contables para Siigo según servicio, tipo de corte y sede.
10. `lote_exportacion` y `asiento_contable`: Cabecera y líneas de exportación para Siigo en doble partida balanceada.

### 2.5. Pruebas de Arquitectura con ArchUnit (`ArchitectureLayerRulesTest.java`)
Se definieron 3 reglas arquitecturales automáticas evaluadas en cada build:
1. `layered_architecture_is_respected`:
   - `Web` no puede ser accedida por ninguna capa.
   - `Service` solo puede ser accedida por `Web` e `Infrastructure`.
   - `Domain` solo puede ser accedida por `Service`, `Repository`, `Infrastructure` y `Web`.
2. `web_must_not_access_repository_directly`: Los controladores web tienen prohibido interactuar directamente con repositorios; siempre deben orquestarse a través de la capa `Service`.
3. `no_cycles_between_packages`: No se permiten dependencias cíclicas entre paquetes.

---

## 3. Pruebas y Validación de la Compuerta de Cierre

| Criterio de Aceptación | Prueba Asociada | Resultado |
|---|---|:---:|
| Contexto Spring levanta limpiamente | `CorporateBillingServiceApplicationTests.contextLoads()` | **PASS** |
| Migración Flyway `V1` aplica sobre PostgreSQL | `PostgresConnectionTest` (Testcontainers) | **PASS** |
| Reglas de arquitectura ArchUnit pasan al 100% | `ArchitectureLayerRulesTest` (3 reglas) | **PASS** |
| Endpoint `/actuator/health` configurado | `application.yml` + Actuator context | **PASS** |
| Build ejecutable sin errores | `./gradlew test` / `./gradlew build` | **PASS** |

---

## 4. Entregable del Sprint 0

- Proyecto base compilable en Java 21 / Spring Boot 4.1.1.
- Estructura de paquetes DDD-Lite establecida y protegida por ArchUnit.
- Esquema DDL en `db/migration/V1__esquema_inicial.sql` verificado en base de datos PostgreSQL real.
