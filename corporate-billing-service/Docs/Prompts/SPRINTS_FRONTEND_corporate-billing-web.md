# Sprints de frontend — `corporate-billing-web` (Angular)

> Complemento a `PROMPTS_POR_SPRINT_corporate-billing.md` y `SPRINT_8_AUTENTICACION_corporate-billing.md`. Pega **un sprint a la vez** en tu agente (IntelliJ). Estos sprints arrancan **después de cerrar el backend** (S0–S8): el microservicio ya expone su API y valida tokens.

---

## Contexto y decisiones fijas

- **Stack:** Angular (última LTS), TypeScript, componentes *standalone*, *signals* para estado, `HttpClient` con interceptores, Angular Router con *lazy loading* por módulo funcional.
- **Proyecto aparte**: repositorio/carpeta propia `corporate-billing-web`, **contenedor propio servido por nginx**. No se empaqueta dentro del microservicio.
- **Convención bilingüe** (la misma del backend): **inglés** para identificadores de código (componentes, servicios, variables, carpetas) y **español** para todo lo visible al usuario, la narrativa y la documentación. Los términos fiscales se conservan tal cual (`nit`, `iva`, `retefuente`, `reteica`, `reteiva`, `F1`, `F20`, `prorrateo`, `cufe`).
- **Referencia visual y funcional:** el MVP `index.html` ya construido (paleta, layout SP, flujo de las pantallas, representación gráfica de la factura Siigo). Se **reimplementa en Angular**, no se copia el HTML tal cual.
- **Reglas de negocio innegociables** que el front debe hacer evidentes:
  1. El **valor de la factura nunca cambia**; las retenciones solo afectan el valor a pagar.
  2. La **configuración de impuestos es general**, no por cliente: el corporativo **elige** al pagar.
  3. El **asiento contable debe cuadrar** (Σ débitos = Σ créditos) y la factura **no muestra retenciones**.

**Compuerta de cierre (todos los sprints):** pruebas unitarias en verde · `ng build` de producción sin errores ni warnings nuevos · lint en verde · cada historia validada contra sus criterios de aceptación · revisión de código aprobada · sin claves ni URLs quemadas en el código.

---

## Resumen

| Sprint | Objetivo | Duración |
|---|---|---|
| **F0** | Scaffold, diseño base y shell | 1 semana |
| **F1** | Autenticación, roles y guardas | 1.5 semanas |
| **F2** | Capa de datos y sincronización | 1.5 semanas |
| **F3** | Facturas: listado, filtros y detalle | 2 semanas |
| **F4** | Representación gráfica y ajustes | 1.5 semanas |
| **F5** | Impuestos y liquidación de pago | 2 semanas |
| **F6** | Auditoría, pulido y despliegue | 1.5 semanas |

**Total estimado: ~11 semanas.**

---

## F0 — Scaffold, diseño base y shell · 1 semana

Crea el proyecto `corporate-billing-web` y deja la base sobre la que se construye todo.

**Tareas**
- Proyecto Angular con componentes standalone, routing y SCSS. Estructura por carpetas: `core/` (servicios transversales, interceptores, guardas), `shared/` (componentes reutilizables, pipes, directivas), `features/` (un módulo por dominio funcional), `layout/`.
- **Sistema de diseño** a partir de la paleta del MVP: variables SCSS con acento `#1F6F5C`, tinta `#1A2B34`, fondo `#F7F9F8`, y los estados semántico éxito/error/aviso. Tipografía Inter. Tokens de radio, sombra y espaciado.
- **Shell**: barra superior SP (logo, notificaciones, usuario), sidebar flotante colapsable con navegación, topbar con selectores globales de **periodo** y **corte**, y contenedor de contenido. Responsive: sidebar como *drawer* por debajo de 1024px.
- Componentes compartidos base: botón, tarjeta, tabla con estados, badge, modal, toast, spinner, **estado vacío** y **banner de error**.
- **Pipes** propios: moneda COP (punto de miles, sin decimales) y fecha `DD/MM/AAAA`.
- Configuración por entornos (`environment.ts` / `environment.prod.ts`) con la URL base de la API, sin valores quemados en componentes.

**Pruebas:** el shell renderiza y navega entre rutas vacías; los pipes formatean correctamente; build de producción en verde.

**Entregable:** aplicación navegable con el diseño SP y rutas placeholder por módulo.

---

## F1 — Autenticación, roles y guardas · 1.5 semanas

Integra el proveedor de identidad corporativo (NUPLIN / SSO), alineado con el Sprint 8 del backend. El front **no valida el token criptográficamente** — eso lo hace el microservicio — pero sí gestiona la sesión y adapta la interfaz al rol.

**Tareas**
- Flujo de inicio de sesión contra el proveedor (redirección/SSO), retorno y almacenamiento seguro del token en memoria; nunca en `localStorage` si puede evitarse.
- **Interceptor HTTP** que adjunta el token a cada petición hacia la API.
- Manejo de **401** (sesión expirada → renovar o redirigir al login sin perder la ruta destino) y **403** (sin permiso → pantalla clara de acceso denegado, no un error genérico).
- `AuthService` con el usuario actual (nombre, correo, roles) expuesto por *signals*.
- **Guardas de ruta por rol**: analista, administrador. Las rutas de **catálogo de impuestos** solo para administrador.
- Directiva estructural para **ocultar o deshabilitar** acciones según el rol (no basta con ocultar: la API también protege).
- Cerrar sesión, y aviso de expiración próxima.

**Pruebas:** el interceptor adjunta el token; un 401 redirige al login; un 403 muestra acceso denegado; las guardas bloquean rutas por rol; la interfaz oculta acciones no permitidas.

**Entregable:** aplicación con sesión real y navegación protegida.

---

## F2 — Capa de datos y sincronización · 1.5 semanas

Conecta el front con la API y construye el primer módulo funcional.

**Tareas**
- **Servicios de API tipados** (un servicio por área: facturas, lotes, órdenes, impuestos, pagos) con modelos/interfaces TypeScript que reflejen los contratos del backend. Ningún componente llama a `HttpClient` directamente.
- Manejo centralizado de errores: traduce el formato `{ codigo, mensaje, detalles }` del backend a mensajes de usuario, con reintento donde aplique.
- **Vista Resumen**: tarjetas con facturas del periodo, valor total, sedes consolidadas y estado del lote.
- **Panel de última sincronización**: fecha y hora del último ciclo, facturas nuevas detectadas, notas crédito cruzadas y próximo ciclo (la lectura de Siigo corre **cada hora**). Botón **"Sincronizar ahora"** para facturas urgentes que no pueden esperar el ciclo.
- **Carga manual por archivo** como alternativa: zona de arrastrar/soltar, barra de progreso, y reporte de resultado con creados / actualizados / con error, más la **tabla de errores por línea** (los errores no bloquean el resto). Tabla con búsqueda y paginación.

**Pruebas:** servicios con `HttpTestingController`; estados de carga, vacío y error en cada pantalla; el reporte de importación muestra correctamente los errores parciales.

**Entregable:** Resumen y Sincronización funcionando contra la API real.

---

## F3 — Facturas: listado, filtros y detalle · 2 semanas

El módulo central de consulta, con lo que pidió cartera expresamente.

**Tareas**
- **Listado** con paginación del lado del servidor, orden y densidad legible.
- **Filtros**: por **nombre**, **NIT**, **rango de fechas** y **número de factura** (los cuatro son requisito explícito). Los filtros se reflejan en la URL para poder compartir la búsqueda.
- Columnas con **fecha de emisión y fecha de vencimiento por factura**: no hay una fecha única del periodo — unas vencen el 16, otras corridas al 25, y la facturación surge a lo largo del mes.
- Cuando el archivo de origen **no trae la razón social**, muestra el NIT con una marca visible de *nombre no disponible*; **nunca inventes un nombre**.
- Filtros por **tipo** (F1 corporativos / F20 Meganet / Enlaces) y exclusión por defecto de **residenciales masivas**, con opción de verlas.
- **Detalle de factura**: snapshot fiscal del cliente, **ítems por sede** con prorrateo visible, IVA por ítem y totales, más el **indicador de cuadre** (Σ ítems = cabecera) destacado en verde o rojo.
- Estado **"Con nota crédito"**: valor de la nota, saldo real y enlace a la nota que la afecta.

**Pruebas:** cada filtro aplica y se refleja en la URL; el detalle calcula y muestra el cuadre; una factura con nota crédito muestra su saldo real.

**Entregable:** consulta de facturas completa y usable para el día a día de cartera.

---

## F4 — Representación gráfica y ajustes · 1.5 semanas

**Tareas**
- **Componente de representación gráfica de la factura** en el formato oficial de Siigo: encabezado del emisor (SP SISTEMAS PALACIOS LTDA, NIT 900.163.149-2, Pasto-Nariño), logo por marca (Sistemas Palacios para F1 / MEG@NET para F20), recuadro con prefijo y número (**FESP** para F1, **FEMG** para F20), datos del cliente, tabla de ítems con código y descripción (`SEDE.INT.FIB.<TIPO>.<VELOCIDAD> GR <DD-DD MMM AAAA>`), totales, forma de pago, valor en letras, observaciones, bloque de autorización DIAN y CUFE.
- Formato de importes **propio de este documento**: coma de miles y dos decimales (`5,694,150.00`), distinto al del resto de la aplicación.
- **Impresión / guardar como PDF** con hoja `@media print`: tamaño carta, sin shell ni botones.
- **La factura no muestra retenciones**: `Total A Pagar = Total Bruto + IVA`.
- Descarga del **PDF oficial** capturado de Siigo cuando exista (`ruta_pdf`).
- **Anulación** con motivo y **emisión de nota crédito** ligada a su factura, con confirmación explícita por ser operaciones sensibles.

**Pruebas:** el documento refleja todos los ítems y el prorrateo en la descripción; los totales cuadran y no aparece ninguna retención; la impresión sale limpia; anular exige motivo.

**Entregable:** factura presentable al cliente e imprimible, más los ajustes.

---

## F5 — Impuestos y liquidación de pago · 2 semanas

El módulo redefinido en la última reunión. **No hay configuración tributaria por cliente.**

**Tareas**
- **Catálogo general de impuestos y retenciones** (solo administrador): tipo (ReteFuente, ReteICA, ReteIVA, Estampilla, Otro), etiqueta visible, porcentaje, base mínima, vigencia desde/hasta, estado y **versión**. Pueden coexistir varios conceptos del mismo tipo (ej. ReteFuente 4% servicios y 3.5% software) y **varias estampillas**. Un cambio de base **crea una versión nueva** y cierra la anterior; no se sobrescribe.
- **Liquidación de pago**: facturas pendientes del NIT con su vencimiento; **selección múltiple y opcional** de retenciones desde el catálogo; por cada concepto se indica si **aplica o no** según la base mínima frente al valor de la factura.
- **Cálculo correcto de bases**: ReteFuente, ReteICA y estampillas sobre la **base gravable**; **ReteIVA sobre el valor del IVA**. Nunca sobre el total.
- **Redondeo al peso** en el neto final, mostrando la diferencia por redondeo (las entidades públicas redondean).
- **Pago parcial**: elegir cuáles facturas se pagan; las no pagadas quedan pendientes con aviso del tipo *"Gracias por su pago. Recuerde que su factura de septiembre continúa pendiente."* Indicador de estado de cartera del cliente.
- Salidas descargables: **recibo de caja** imprimible y la **interfaz de pago para Siigo** (cruce factura↔pago con cuenta de banco parametrizable y cuentas de los impuestos retenidos).
- Junto a cada retención aplicada, mostrar la **versión/vigencia** usada.

**Pruebas:** un caso por cada base de cálculo (especialmente ReteIVA sobre el IVA); un concepto por debajo de la base mínima no se descuenta; el valor de la factura no cambia nunca; pago parcial deja la otra factura pendiente; el neto queda redondeado.

**Entregable:** catálogo administrable y liquidación de pago completa.

---

## F6 — Auditoría, pulido y despliegue · 1.5 semanas

**Tareas**
- **Auditoría**: cambios del catálogo (valor anterior → nuevo, usuario, fecha) e **histórico de pagos** con las retenciones aplicadas y **el porcentaje y la base exactos** con que se liquidó cada uno. Ese registro es **inmutable**: si el catálogo cambia después, los pagos ya hechos conservan su versión.
- **Módulo de lote Siigo** terminado: tabla del asiento (secuencia, cuenta, D/C, valor, descripción), resumen Σ Débitos / Σ Créditos con el cuadre destacado, vista previa del plano y descarga.
- **Accesibilidad**: contraste, foco visible, navegación por teclado, etiquetas en formularios, roles ARIA en tablas y modales.
- **Rendimiento**: *lazy loading* por módulo, `trackBy` en listas, virtualización si una tabla supera el millar de filas, revisión del tamaño del bundle.
- **Pruebas end-to-end** del flujo completo: iniciar sesión → sincronizar → generar facturación → consultar factura → ver representación gráfica → generar lote → liquidar un pago.
- **Despliegue**: `Dockerfile` multietapa (build de Angular + **nginx** que sirve los estáticos), configuración de nginx con *fallback* a `index.html` para las rutas del router, compresión y cabeceras de caché; servicio en el `docker-compose.yml` junto al microservicio y la base de datos; variables de entorno inyectadas en tiempo de ejecución (no quemadas en el build).
- `README.md` del front con instalación, entornos, scripts y despliegue.

**Pruebas:** e2e del flujo completo en verde; la imagen de nginx sirve la aplicación y las rutas profundas no dan 404; auditoría conserva versiones históricas.

**Entregable:** frontend completo, probado y desplegable en Docker junto al microservicio.

---

## Historias de usuario del frontend

### E10 — Interfaz de facturación corporativa

**HU-38 — Iniciar sesión con las credenciales corporativas.**
Como analista, quiero entrar al portal con el SSO corporativo, para no manejar un usuario aparte.
- CA1: Al iniciar sesión correctamente, el sistema muestra el portal con el nombre del usuario.
- CA2: Cuando la sesión expira, el sistema avisa y lleva al login conservando la ruta a la que se iba.
- CA3: Al cerrar sesión, se limpia la sesión y no se puede volver con el botón atrás.

**HU-39 — Ver solo lo que mi rol permite.**
Como administrador, quiero que cada usuario vea únicamente sus opciones, para evitar cambios indebidos.
- CA1: El catálogo de impuestos solo aparece y es accesible para el administrador.
- CA2: Si un analista intenta entrar por URL directa, ve una pantalla de acceso denegado.

**HU-40 — Conocer el estado de la última sincronización.**
Como analista, quiero ver cuándo fue la última lectura de Siigo y qué trajo, para saber si la información está al día.
- CA1: El resumen muestra fecha y hora del último ciclo, facturas nuevas y notas crédito cruzadas.
- CA2: Puedo forzar una sincronización cuando hay una factura urgente.

**HU-41 — Buscar facturas por varios criterios.**
Como analista de cartera, quiero buscar por nombre, NIT, rango de fechas o número de factura, para encontrar rápido lo que necesito.
- CA1: Cada filtro devuelve resultados correctos y pueden combinarse.
- CA2: La búsqueda queda reflejada en la URL y puede compartirse.
- CA3: Si el archivo no trae la razón social, se muestra el NIT con la marca de nombre no disponible.

**HU-42 — Revisar el detalle y el cuadre de una factura.**
Como analista, quiero ver los ítems por sede y verificar que los totales cuadren, para confiar en la factura antes de enviarla.
- CA1: El detalle lista un ítem por sede con su prorrateo, IVA y total.
- CA2: El indicador muestra en verde si la suma de ítems coincide con la cabecera y en rojo si no.

**HU-43 — Obtener la representación gráfica de la factura.**
Como analista, quiero ver e imprimir la factura en el formato oficial, para entregársela al cliente.
- CA1: El documento reproduce el formato de Siigo con el prefijo correcto (FESP para F1, FEMG para F20).
- CA2: Muestra `Total A Pagar = Total Bruto + IVA` y **ninguna retención**.
- CA3: Al imprimir, sale solo el documento, sin la interfaz del portal.

**HU-44 — Administrar el catálogo de impuestos.**
Como administrador, quiero crear y versionar los conceptos de retención generales, para que estén disponibles al momento del pago.
- CA1: Puedo crear varios conceptos del mismo tipo y varias estampillas.
- CA2: Al cambiar la base, el sistema crea una versión nueva y cierra la vigencia anterior.
- CA3: Los conceptos inactivos no aparecen para seleccionar al pagar.

**HU-45 — Liquidar el pago eligiendo las retenciones.**
Como corporativo, quiero seleccionar qué retenciones me aplican al pagar, para pagar el valor neto correcto.
- CA1: La selección es opcional y múltiple; sin selección se paga el valor completo.
- CA2: Un concepto cuya base mínima supera el valor de la factura se marca como no aplicable y no se descuenta.
- CA3: ReteFuente, ReteICA y estampillas se calculan sobre la base gravable y ReteIVA sobre el IVA.
- CA4: El valor de la factura no cambia en ningún momento; solo cambia el neto a pagar.
- CA5: El neto final se muestra redondeado al peso, con la diferencia por redondeo visible.

**HU-46 — Pagar solo algunas de mis facturas pendientes.**
Como corporativo con varias facturas, quiero pagar una y dejar las otras, para manejar mi flujo de caja.
- CA1: Puedo elegir cuáles facturas pagar.
- CA2: Al confirmar, las pagadas cambian de estado y las demás siguen pendientes con su aviso.

**HU-47 — Descargar el soporte del pago.**
Como analista, quiero el recibo de caja y la interfaz para Siigo, para cruzar el pago en contabilidad.
- CA1: Puedo descargar el recibo de caja con las facturas cruzadas y las retenciones aplicadas.
- CA2: Puedo descargar la interfaz de pago con la cuenta de banco y las cuentas de los impuestos.

**HU-48 — Consultar el histórico con la versión aplicada.**
Como administrador, quiero ver con qué porcentaje y base se liquidó cada pago, para auditar.
- CA1: Cada pago registra las retenciones con su porcentaje, base y versión.
- CA2: Un cambio posterior del catálogo no altera los pagos ya registrados.

**HU-49 — Validar el lote antes de subirlo a Siigo.**
Como analista, quiero ver el asiento y confirmar que cuadra, para no subir un archivo con errores.
- CA1: El sistema muestra las líneas del asiento con su naturaleza y valor.
- CA2: Σ Débitos y Σ Créditos se muestran destacados e indican si cuadran.
- CA3: Puedo descargar el archivo plano del lote.

---

## Pendientes por confirmar

1. Flujo exacto del SSO desde el navegador (redirección, retorno, refresco) con el equipo de identidad.
2. Si el portal de pago del corporativo vive en este frontend o en la pasarela (hoy la liquidación está aquí por conveniencia del MVP).
3. Contratos de paginación y orden de los endpoints de listado.
4. Dominio y certificado para el despliegue del front.
