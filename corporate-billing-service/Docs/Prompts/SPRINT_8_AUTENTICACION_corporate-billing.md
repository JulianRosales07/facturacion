# Sprint 8 — Autenticación y autorización · `corporate-billing-service`

> Complemento a `PROMPTS_POR_SPRINT_corporate-billing.md`. Contiene el **prompt del sprint** (para pegar en el agente) y las **historias de usuario** con sus criterios de aceptación. Igual que los demás sprints: no se cierra hasta que su compuerta esté en verde.

---

## Contexto

El microservicio **no implementa su propio login ni gestiona contraseñas**: el proveedor de identidad corporativo (NUPLIN / SSO de Sistemas Palacios) expone los endpoints de autenticación y emite los tokens. Este servicio actúa como **servidor de recursos**: recibe el token en cada petición, lo **valida** y autoriza según el rol. Datos conocidos del proveedor: firma **RS256/RSA**, entorno **QA** con issuer `qa.sistemaspalacios.com`, registro en producción pendiente.

Consumidores de la API: el **panel de facturación** (analistas y administradores), la **pasarela de pagos** (servicio a servicio, para consultar facturas y el PDF) y el **scheduler** interno (sincronización horaria).

---

## Prompt del sprint (pegar en el agente)

**Sprint 8 — Autenticación y autorización.** Trabaja sobre `PROMPT_corporate-billing-service.md`. Protege la API del microservicio validando los tokens que emite el proveedor de identidad corporativo. **No construyas login, registro, recuperación de contraseña ni almacenamiento de credenciales**: eso vive en el proveedor.

Duración estimada: **1.5 semanas**.

### Tareas

1. **Dependencias y configuración**
   - Agrega `spring-boot-starter-security` y `spring-boot-starter-oauth2-resource-server` al `build.gradle`.
   - Configura el servicio como **resource server JWT**: valida firma con la clave pública del proveedor vía **JWKS** (`issuer-uri` / `jwk-set-uri`), con algoritmo **RS256**. Nada de secretos simétricos ni llaves en el repositorio.
   - Todo configurable por entorno (`AUTH_ISSUER_URI`, `AUTH_JWKS_URI`, `AUTH_AUDIENCE`), presente en `.env.example` y en el `docker-compose.yml`. `<PENDIENTE: URLs, audiencia y claims exactos que entregará el equipo de identidad>`.

2. **Validación del token**
   - Verifica firma, **expiración**, **issuer** y **audiencia**. Rechaza cualquier token que falle alguna.
   - Cachea el JWKS y refréscalo ante rotación de llaves, sin reiniciar el servicio.
   - Un token ausente o inválido devuelve **401**; un token válido sin permiso suficiente devuelve **403**. Ambos con el formato de error JSON consistente del servicio (`codigo`, `mensaje`, `detalles`), **sin filtrar detalles internos** del token ni trazas.

3. **Roles y autorización por endpoint**
   Mapea los claims de rol/scope del token a autoridades de Spring Security y protege la API:
   - `ROL_ANALISTA` — sincronizar, generar facturación, consultar facturas, generar y descargar lotes, emitir notas crédito.
   - `ROL_ADMINISTRADOR` — todo lo del analista, más el **catálogo de impuestos y retenciones** y la configuración (escritura).
   - `ROL_PASARELA` (servicio a servicio) — **solo lectura** de facturas pendientes por NIT y descarga del PDF. Sin acceso a generación, lotes ni configuración.
   - `ROL_SISTEMA` (scheduler) — disparar la sincronización.
   Aplica la seguridad a nivel de método (`@PreAuthorize`) en la capa `web`/`service`, **sin que `domain` conozca nada de seguridad** (ArchUnit debe seguir en verde).

4. **Endpoints públicos**
   - Deja abiertos únicamente `/actuator/health` y `/actuator/info`. El resto de Actuator queda restringido a `ROL_ADMINISTRADOR`.
   - Todo `/api/v1/**` requiere autenticación.

5. **Identidad en el contexto y auditoría**
   - Extrae del token el identificador del usuario (`sub`, nombre, correo) y hazlo accesible a los servicios de aplicación.
   - Registra **quién** ejecutó las operaciones sensibles: generación de facturación, anulación, nota crédito, generación/confirmación de lote y cambios en el catálogo de impuestos. Guarda el usuario junto al registro correspondiente.
   - Logs de negocio con el usuario, **nunca con el token ni credenciales**.

6. **Propagación hacia el sistema de Órdenes**
   - Si los endpoints de Órdenes requieren token, el cliente HTTP debe obtenerlo/propagarlo del mismo proveedor, con renovación automática al expirar y reintento de la llamada fallida por 401. Configurable.

7. **CORS**
   - Configura orígenes permitidos por entorno (el panel y la pasarela), sin comodines en producción.

### Pruebas

- Unitarias del mapeo claims → roles.
- Integración con un **servidor de autorización simulado**: token válido → 200; token expirado, firmado con otra llave, con issuer o audiencia incorrectos, o ausente → **401**; token válido con rol insuficiente → **403**.
- Un test por rol que verifique el acceso permitido y el denegado (especialmente que `ROL_PASARELA` no pueda generar ni configurar nada).
- ArchUnit sigue pasando: `domain` no importa Spring Security.
- Verifica que ningún endpoint de `/api/v1/**` quedó sin protección.

### Compuerta de cierre

La estándar de todos los sprints, más: **ningún endpoint de negocio accesible sin token válido**, y ninguna credencial, llave o secreto en el repositorio.

---

## Historias de usuario

### E9 — Autenticación y autorización

**HU-30 — Acceso autenticado a la API.**
Como analista, quiero acceder al sistema con las credenciales corporativas del proveedor de identidad, para no manejar un usuario aparte.
- CA1: Cuando la petición trae un token válido y vigente, el sistema permite la operación.
- CA2: Cuando no hay token o es inválido, el sistema responde 401 sin ejecutar nada.
- CA3: Cuando el token está expirado, el sistema responde 401 indicando que la sesión venció.

**HU-31 — Validación criptográfica del token.**
Como sistema, quiero validar la firma, el issuer y la audiencia de cada token contra el proveedor, para impedir tokens falsificados.
- CA1: Cuando el token está firmado con una llave distinta a la publicada por el proveedor, se rechaza.
- CA2: Cuando el issuer o la audiencia no corresponden, se rechaza.
- CA3: Cuando el proveedor rota sus llaves, el sistema refresca el JWKS y sigue validando sin reiniciarse.

**HU-32 — Permisos por rol.**
Como administrador, quiero que cada rol solo pueda hacer lo que le corresponde, para proteger la facturación y la configuración.
- CA1: El analista puede sincronizar, facturar, consultar y generar lotes.
- CA2: Solo el administrador puede modificar el catálogo de impuestos y retenciones.
- CA3: Cuando un usuario intenta una operación fuera de su rol, el sistema responde 403 y no ejecuta la acción.

**HU-33 — Acceso restringido de la pasarela de pagos.**
Como administrador, quiero que la pasarela solo consulte facturas pendientes y descargue el PDF, para que no pueda alterar la facturación.
- CA1: Con el rol de pasarela se pueden consultar facturas pendientes por NIT y obtener el PDF.
- CA2: Con ese mismo rol, generar facturación, anular, crear lotes o tocar configuración devuelve 403.

**HU-34 — Trazabilidad de quién hizo qué.**
Como administrador, quiero saber qué usuario ejecutó cada operación sensible, para auditar.
- CA1: Al generar facturación, anular, emitir nota crédito, generar/confirmar lote o cambiar el catálogo, queda registrado el usuario del token.
- CA2: Ese registro se puede consultar junto a la operación afectada.
- CA3: Los logs no contienen el token ni datos de credenciales.

**HU-35 — Ejecución autenticada de la sincronización.**
Como sistema, quiero que la sincronización programada se ejecute con su propia identidad de servicio, para no depender de un usuario humano.
- CA1: El scheduler se autentica con el rol de sistema y ejecuta la sincronización.
- CA2: Si el token del servicio expira, se renueva automáticamente y la ejecución continúa.

**HU-36 — Consumo autenticado del sistema de Órdenes.**
Como sistema, quiero autenticarme ante los endpoints de Órdenes, para poder traer suscripciones y novedades.
- CA1: El cliente HTTP adjunta el token requerido en cada llamada.
- CA2: Ante un 401 del sistema de Órdenes, renueva el token y reintenta una vez antes de marcar el error.
- CA3: Las credenciales salen de variables de entorno, nunca del código.

**HU-37 — Salud del servicio sin autenticación.**
Como operador, quiero consultar el estado del servicio sin token, para monitorearlo desde Docker o el balanceador.
- CA1: `/actuator/health` responde sin autenticación.
- CA2: El resto de endpoints de Actuator requiere rol de administrador.

---

## Requerimientos derivados

- **RF-31** El sistema valida en cada petición un token JWT RS256 emitido por el proveedor de identidad corporativo, verificando firma, expiración, issuer y audiencia.
- **RF-32** El sistema autoriza cada operación según el rol contenido en el token (analista, administrador, pasarela, sistema).
- **RF-33** El sistema registra el usuario autenticado en cada operación sensible (facturación, anulación, notas crédito, lotes, catálogo de impuestos).
- **RF-34** El sistema se autentica ante los endpoints del sistema de Órdenes y renueva el token al expirar.
- **RF-35** El sistema expone `/actuator/health` sin autenticación y protege el resto de la API.
- **RNF-23** El servicio no almacena contraseñas ni emite tokens: delega la autenticación en el proveedor de identidad.
- **RNF-24** Ninguna llave, secreto o credencial reside en el repositorio; todo va por variables de entorno.
- **RNF-25** Las respuestas de error de seguridad (401/403) no revelan detalles internos del token ni trazas.

---

## Pendiente por confirmar con el equipo de identidad

1. URLs de `issuer-uri` / `jwk-set-uri` para QA y producción.
2. Nombre exacto del claim de roles/scopes y sus valores.
3. Audiencia (`aud`) asignada a este microservicio.
4. Flujo para servicio a servicio (pasarela y scheduler): ¿client credentials?
5. Tiempo de vida del token y política de refresco.
6. Si los endpoints del sistema de Órdenes usan el mismo proveedor o credenciales propias.
