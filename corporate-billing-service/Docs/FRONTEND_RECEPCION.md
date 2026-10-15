# Panel de recepción de facturas

## Funcionalidad incorporada

- Navegación centrada en resumen, facturas recibidas y sincronización. Las pantallas anteriores de pagos, impuestos, generación y notas crédito ya no tienen rutas activas en este panel.
- Resumen basado en la API: total de facturas recibidas, documentos con PDF asociado, pendientes de PDF, última sincronización y últimas facturas por fecha de emisión.
- Consulta paginada en el servidor con total real. Filtros por cliente/NIT, consecutivo, F1/F20, periodo, rango de fechas y disponibilidad de PDF. Los filtros permanecen en la URL al ir al detalle y regresar.
- Detalle de solo lectura con datos recibidos, partidas, cantidades, bases, IVA y total oficial. Los campos ausentes se indican como no informados.
- Vista previa del PDF oficial mediante descarga autenticada, URL temporal local y liberación de memoria al salir de la vista. Descarga autenticada y mensaje específico cuando falta el documento.
- Sincronización manual, actualización del estado cada 15 segundos e historial paginado de archivos de datos con fecha, origen, usuario, nuevos, repetidos y causa de rechazo.
- Validación de tamaño/formato antes de la carga manual. No hay facturas ni cifras de demostración como sustituto de errores de la API.
- Las fechas civiles de factura se muestran sin desplazarlas al día anterior por la zona horaria.

## Acceso corporativo: preparación y límite actual

El usuario confirmó que aún no tiene los datos de SSO. El panel **no permite iniciar una sesión simulada**. Sin configuración muestra «SSO pendiente de configuración» y deshabilita el botón de acceso.

Está preparado un flujo OAuth 2.0 Authorization Code con PKCE S256 para una aplicación pública de navegador. No recibe contraseñas corporativas ni guarda secretos de cliente. La compatibilidad del proveedor y su registro de aplicación deben ser confirmados por TI; no se ha probado contra el SSO real.

TI debe completar `corporate-billing-service-web/public/auth-config.json` (en el despliegue, `/auth-config.json`):

```json
{
  "authorizationEndpoint": "",
  "tokenEndpoint": "",
  "clientId": "",
  "scope": "openid profile email"
}
```

Valores requeridos:

1. URL HTTPS de autorización y URL HTTPS de intercambio de códigos por tokens.
2. Client ID de una aplicación **pública**, con PKCE S256. No introducir un client secret en el frontend.
3. Registrar como callback la URL exacta `https://HOST-DEL-PANEL/auth/callback`. Para desarrollo local: `http://localhost:4200/auth/callback`.
4. Permitir el origen del panel en CORS del endpoint de tokens. El backend también debe permitir el origen del frontend cuando se despliegan por separado.
5. Emitir un access token aceptado por el backend, con emisor, audiencia y JWKS coherentes con `AUTH_ISSUER_URI`, `AUTH_AUDIENCE` y `AUTH_JWKS_URI`, y rol de analista o administrador.

El panel intercambia el código y consulta `GET /api/v1/sesion` antes de guardar la sesión. Este endpoint está protegido por la validación JWT del backend y devuelve identidad, vencimiento y roles. El navegador no asigna un administrador ficticio ni decide la autenticidad a partir de un JWT meramente decodificado.

El token se guarda en `sessionStorage` y solo se adjunta a solicitudes destinadas a la API configurada. Los tokens de demostración antiguos de `localStorage` se descartan. Al vencer la sesión, la navegación exige volver al acceso conservando una ruta interna de retorno. El cierre de sesión limpia la sesión local; no cierra globalmente la sesión del proveedor corporativo. La renovación automática de tokens no está activada: se vuelve a ingresar por SSO.

El servidor web debe servir `index.html` para rutas del frontend, incluida `/auth/callback`, y enrutar `/api/v1` hacia el backend cuando comparten dominio.

## Backend que acompaña al panel

| Endpoint | Propósito |
| --- | --- |
| `GET /api/v1/sesion` | Identidad validada del usuario del panel. |
| `GET /api/v1/facturas/recibidas` | Página de facturas de origen SIIGO. Parámetros `search`, `number`, `type`, `period`, `from`, `to`, `pdfAvailable`, `page`, `size`. |
| `GET /api/v1/facturas/recibidas/resumen` | Contadores globales de facturas recibidas y PDF asociados. |
| `GET /api/v1/siigo/facturas/historial` | Historial de archivos de datos paginado. |

Los endpoints anteriores de detalle, PDF, carga manual y sincronización se mantienen. La respuesta paginada contiene `items`, `page`, `size`, `totalElements` y `totalPages`. Los filtros se aplican **antes** de paginar.

La migración **V15** crea `historial_recepcion_archivo`. Los nuevos registros de auditoría sobreviven al rollback de una importación rechazada. El historial comienza al desplegar esta versión; no reconstruye intentos anteriores. Cada archivo rechazado queda sin importar como unidad; el historial no pretende contar filas que no se pudieron interpretar. Los resultados de PDF siguen apareciendo en el detalle del ciclo de sincronización.

La disponibilidad indica que hay una ruta PDF asociada. Si el almacenamiento se vuelve inaccesible después, la descarga reporta el error; mantener el volumen local persistente forma parte de la operación.

## Verificación

Ejecutar desde cada proyecto:

```powershell
# Backend
.\gradlew.bat test --console=plain

# Frontend
npm test -- --watch=false
npm run build
```

Se incluyen pruebas de estado OAuth y rechazo del acceso sin configuración, validación del perfil en backend, aislamiento del Bearer respecto a otros dominios, paginación con más de 50 facturas, filtros previos a la paginación, auditoría de rechazos, fechas civiles y recorrido del router desde consulta hasta detalle, visualización y descarga de PDF.

El recorrido integrado utiliza HTTP y proveedor de identidad controlados **solo en las pruebas**. No sustituye la aceptación con el SSO y la carpeta reales. Para esa aceptación siguen pendientes el registro de TI, la ruta/permisos de la carpeta y la confirmación del formato final de datos.

## Estado del análisis estático

El análisis estático de los archivos del panel de recepción pasa. `npm run lint` sobre todo el repositorio todavía detecta problemas en módulos anteriores fuera de las rutas activas (cartera, pagos, impuestos, órdenes y lotes). No se deshabilitaron reglas ni se ocultaron esos archivos para cambiar ese resultado; su saneamiento global queda separado de este alcance.
