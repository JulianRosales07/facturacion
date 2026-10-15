# Sprint 08 — Autenticación y Autorización

## 1. Resumen Ejecutivo

El **Sprint 08** incorporó la infraestructura integral de seguridad perimetral y granular para `corporate-billing-service`. El microservicio opera como **Resource Server OAuth2** validando tokens JWT con algoritmo **RS256** emitidos por el proveedor de identidad corporativo (NUPLIN / SSO de Sistemas Palacios) contra el endpoint público de claves **JWKS**, abstrayendo por completo el manejo de credenciales, inicios de sesión o contraseñas locales.

Se implementó el modelo de control de acceso basado en roles (**RBAC**), protegiendo la totalidad de los endpoints de negocio bajo `/api/v1/**`, segregando las capacidades operativas entre analistas, administradores, pasarela de pagos y tareas programadas del sistema. Asimismo, se estableció la trazabilidad y auditoría de identidad en todas las operaciones sensibles (facturación, anulación, notas crédito, lotes y configuración tributaria) y se securizó la propagación de llamadas HTTP hacia el sistema de Órdenes con soporte de auto-renovación ante respuestas 401.

---

## 2. Historias de Usuario y Criterios de Aceptación Cumplidos

### HU-30 — Acceso autenticado a la API
- **CA1**: Peticiones con token Bearer válido, emitido por el IdP corporativo y con firma verificada, acceden a los recursos autorizados.
- **CA2**: Peticiones sin token o con formato incorrecto retornan inmediatamente `HTTP 401 Unauthorized` con código `NO_AUTORIZADO`.
- **CA3**: Tokens vencidos (`exp`) son rechazados con `HTTP 401 Unauthorized` sin procesar la lógica de negocio.

### HU-31 — Validación criptográfica del token
- **CA1**: Tokens con firma no coincidente o generados con llaves ajenas al set JWKS son descartados.
- **CA2**: El validador `AudienceAndIssuerValidator` corrobora estrictamente que el claim `iss` coincida con `auth.jwt.issuer-uri` y el claim `aud` contenga la audiencia configurada del microservicio (`corporate-billing-service`).
- **CA3**: El `NimbusJwtDecoder` nativo consulta y almacena en caché las llaves públicas publicadas en el endpoint JWKS, adaptándose a la rotación de certificados sin interrupción de servicio.

### HU-32 — Permisos por rol (RBAC)
- **CA1**: `ROL_ANALISTA` puede consultar y generar facturas, consultar y generar lotes de Siigo, y emitir notas de ajuste.
- **CA2**: `ROL_ADMINISTRADOR` posee las facultades del analista más la gestión y actualización del catálogo tributario (`/api/v1/impuestos/**`).
- **CA3**: Invocaciones fuera del privilegio del rol retornan `HTTP 403 Forbidden` con código `ACCESO_DENEGADO` sin ejecutar la operación.

### HU-33 — Acceso restringido de la pasarela de pagos
- **CA1**: `ROL_PASARELA` tiene autorización restringida para consultar facturas pendientes filtrando por el NIT del cliente (`/api/v1/facturas?nit={nit}`) y descargar sus representaciones PDF.
- **CA2**: Intentos con `ROL_PASARELA` para consultar la cartera global sin NIT, generar facturación masiva, emitir notas crédito o generar lotes contables retornan `HTTP 403 Forbidden`.

### HU-34 — Trazabilidad de quién hizo qué (Auditoría)
- **CA1**: Al generar facturas, anularlas, emitir notas crédito, generar/confirmar lotes de exportación y crear o actualizar vigencias tributarias, se registra el identificador de usuario (`sub`, email o username) extraído del contexto de seguridad.
- **CA2**: Las tablas `factura`, `lote_exportacion`, `documento_ajuste`, `vigencia_concepto` y `perfil_tributario_cliente` persisten las columnas de auditoría correspondientes mediante la migración `V3__auditoria_usuario.sql`.
- **CA3**: Los logs de la aplicación registran el identificador de usuario responsable y omiten estrictamente tokens, cabeceras de autorización o datos confidenciales.

### HU-35 — Ejecución autenticada de la sincronización
- **CA1**: El endpoint de sincronización `/api/v1/sincronizacion` admite invocaciones automatizadas por parte de schedulers autenticados con `ROL_SISTEMA`.
- **CA2**: La arquitectura soporta renovación de credenciales del scheduler para ejecuciones de background continuas.

### HU-36 — Consumo autenticado del sistema de Órdenes
- **CA1**: `HttpOrderSystemClient` inyecta automáticamente la cabecera `Authorization: Bearer <token>` (o `X-API-Key` según configuración de entorno) en cada consulta de suscripciones y novedades.
- **CA2**: Ante un error `401 Unauthorized` retornado por el sistema de Órdenes, el cliente invoca `renewToken()` y reintenta la solicitud una única vez antes de elevar el error.
- **CA3**: Todos los parámetros de conexión y credenciales provienen de variables de entorno (`ORDENES_API_*`).

### HU-37 — Salud del servicio sin autenticación
- **CA1**: `/actuator/health` y `/actuator/info` son públicos para el orquestador (Docker/Kubernetes) y balanceador sin requerir token.
- **CA2**: Los endpoints administrativos de Actuator (`/actuator/metrics`, etc.) exigen `ROL_ADMINISTRADOR`.

---

## 3. Arquitectura y Componentes Desarrollados

```
com.sistemaspalacios.billing/
├── domain/
│   └── model/
│       ├── Invoice.java                    (Campos: createdBy, voidedBy)
│       ├── ExportBatch.java                (Campos: generatedBy, confirmedBy)
│       ├── AdjustmentDocument.java         (Campo: createdBy)
│       ├── TaxConceptValidity.java         (Campos: createdBy, updatedBy)
│       └── ClientTaxProfileEntity.java     (Campo: createdBy)
│       [ArchUnit Green: El dominio NO importa Spring Security]
│
├── service/
│   ├── security/
│   │   └── CurrentUserProvider.java        (Abstracción de identidad desacoplada)
│   ├── BillingServiceImpl.java             (Auditoría en generación y anulación)
│   ├── BatchService.java                   (Auditoría en generación y confirmación)
│   ├── CreditNoteServiceImpl.java          (Auditoría en emisión de notas)
│   └── TaxConfigurationServiceImpl.java    (Auditoría en vigencias de catálogo)
│
├── infrastructure/
│   ├── security/
│   │   ├── SecurityConfig.java             (SecurityFilterChain, CORS, Actuator)
│   │   ├── JwtRoleConverter.java           (Mapeo de claims: roles, groups, scope)
│   │   ├── AudienceAndIssuerValidator.java (Validación estricta de aud e iss)
│   │   ├── RestAuthenticationEntryPoint.java (Respuesta JSON 401 sanitizada)
│   │   ├── RestAccessDeniedHandler.java    (Respuesta JSON 403 sanitizada)
│   │   └── SpringSecurityCurrentUserProvider.java (Extracción de usuario desde JWT)
│   └── ordenes/
│       ├── HttpOrderSystemClient.java      (Bearer token + auto-renewal + 1 retry en 401)
│       └── OrderSystemProperties.java      (Configuración authType, token, renewUrl)
│
└── web/
    └── controller/
        ├── GlobalExceptionHandler.java     (Manejo de AccessDeniedException y AuthenticationException)
        ├── InvoiceController.java          (@PreAuthorize: ANALISTA, ADMIN, PASARELA con NIT)
        ├── BatchController.java            (@PreAuthorize: ANALISTA, ADMIN)
        ├── CreditNoteController.java       (@PreAuthorize: ANALISTA, ADMIN)
        ├── SyncController.java             (@PreAuthorize: SISTEMA, ANALISTA, ADMIN)
        └── TaxConfigurationController.java (@PreAuthorize: ADMIN para escritura)
```

---

## 4. Base de Datos: Migración V3

- Archivo: `src/main/resources/db/migration/V3__auditoria_usuario.sql`
- Contenido DDL:
  - `ALTER TABLE factura ADD COLUMN IF NOT EXISTS creado_por VARCHAR(100);`
  - `ALTER TABLE factura ADD COLUMN IF NOT EXISTS anulado_por VARCHAR(100);`
  - `ALTER TABLE lote_exportacion ADD COLUMN IF NOT EXISTS generado_por VARCHAR(100);`
  - `ALTER TABLE lote_exportacion ADD COLUMN IF NOT EXISTS confirmado_por VARCHAR(100);`
  - `ALTER TABLE documento_ajuste ADD COLUMN IF NOT EXISTS creado_por VARCHAR(100);`
  - `ALTER TABLE vigencia_concepto ADD COLUMN IF NOT EXISTS creado_por VARCHAR(100);`
  - `ALTER TABLE vigencia_concepto ADD COLUMN IF NOT EXISTS actualizado_por VARCHAR(100);`
  - `ALTER TABLE perfil_tributario_cliente ADD COLUMN IF NOT EXISTS creado_por VARCHAR(100);`

---

## 5. Resultados de Pruebas y Validación

La suite completa de pruebas fue ejecutada de manera reproducible:

- **Total de pruebas ejecutadas**: 87
- **Pruebas exitosas**: 86
- **Pruebas omitidas**: 1 (prueba de carga manual)
- **Fallos**: 0
- **Tasa de éxito**: 100%

### Batería específica de seguridad
1. `JwtRoleConverterTest`: Valida el mapeo exhaustivo de roles en listas directas, colecciones de autoridades, objetos de Keycloak (`realm_access.roles`) y cadenas de scope OAuth2 estándar.
2. `SecurityEndpointsIntegrationTest`: 16 pruebas de integración que comprueban los 4 roles principales, la accesibilidad de los endpoints públicos y la correcta emisión de 401/403 con payloads JSON uniformes.
3. `ArchitectureLayerRulesTest`: ArchUnit confirma que la capa de dominio permanece 100% aislada de dependencias de `org.springframework.security..`.
4. `HttpOrderSystemClientResilienceTest`: Verifica el reintento de llamadas HTTP ante errores transitorios y la correcta aplicación de cabeceras de autorización.
