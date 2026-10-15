# Revisión de esquemas — 28 de septiembre de 2026

## Resultado

Los tres scripts anteriores son idénticos byte a byte y están desactualizados respecto al backend:

- `../../shema_completo.sql`
- `shema_completo.sql`
- `shema_simplificado.sql`

Cada uno define 16 tablas. Su sintaxis representa un diseño anterior, pero **no sirve como esquema completo para ejecutar la aplicación actual**. El nombre «completo» o «simplificado» no distingue su contenido.

El archivo independiente **[shema_completo_corregido.sql](shema_completo_corregido.sql)** consolida el resultado real de las migraciones **V1–V15**: 29 tablas, 15 enumeraciones, 37 índices explícitos y 35 claves foráneas. Las claves primarias y restricciones únicas crean además sus propios índices. Se conservaron los originales y las migraciones sin cambios.

## Diferencias corregidas

| Área | Problema en los scripts anteriores | Resultado actualizado |
| --- | --- | --- |
| Recepción Siigo | Sin origen ni clave externa | `origen_documento`, `clave_documento_externo` e índice único para evitar duplicados |
| Varias facturas por mes | Índice limita todas las facturas a una por cliente y periodo | La restricción aplica solo a facturación interna vigente; admite documentos Siigo independientes |
| Documento oficial | Faltan CUFE, ruta PDF y campos actuales de importes | Cabecera compatible con `Invoice`, incluyendo `valor_total` y `neto_a_pagar` |
| PDF y versiones | Sin historial ni estado `REEMPLAZADA` | `factura_pdf_historico`, cadena de versiones y referencias; la referencia a la sustituta se comprueba al finalizar la transacción |
| Importaciones | Sin historial de recepción ni estado de sincronización | `historial_recepcion_archivo`, `estado_sincronizacion` y método de importación |
| Detalle | Sin código de producto ni tarifa base | `codigo_producto` y `valor_mensual_base`; cantidades decimales e importes con precisión de las migraciones |
| Clientes y sedes | Faltan códigos Siigo, municipio, NIC, tarifa y clasificación residencial | Columnas actuales de las entidades; no se mantienen porcentajes tributarios antiguos inexistentes en el modelo vigente |
| Auditoría | Sin varias columnas de usuario | Columnas de creación, anulación, confirmación y regeneración vigentes |
| Compatibilidad heredada | Estructura antigua de `pago_factura` | Cruce entre `pago` y `factura`, retenciones con seis decimales, idempotencia y motivo de ajuste |
| Otros módulos existentes | Faltan catálogos tributarios, cuotas, lotes, mapeos y cartera | Estructuras completas según las migraciones actuales |

## Alcance de tu proyecto

El flujo principal sigue siendo recibir facturas ya emitidas mediante **PDF + archivo de datos** desde la carpeta del servidor. Las tablas de pagos, retenciones, generación, exportación y cartera se conservan porque todavía existen entidades y servicios que dependen de ellas. Tenerlas en el esquema no implica que debas implementar pagos.

`bkp_perfil_tributario_cliente` es una tabla de respaldo heredada que V4 genera incluso en una instalación nueva; aquí queda vacía. Se conserva para reproducir exactamente el resultado de las migraciones. No es un perfil tributario activo ni una tabla que debas alimentar.

El script incluye únicamente datos iniciales de `marca`, `zona`, `tipo_novedad`, `concepto_tributario` y `mapeo_cuenta_contable`. No contiene clientes, facturas, pagos reales, usuarios ni contraseñas. El acceso corporativo sigue dependiendo del SSO externo.

## Uso

**Para la aplicación, la opción preferida sigue siendo que Flyway cree o actualice la base usando las migraciones originales.** Este archivo es una alternativa consolidada para una instalación manual nueva y una referencia completa de la estructura.

Para instalarlo manualmente:

1. Crear una base PostgreSQL nueva que tenga el esquema `public` vacío.
2. Abrir el archivo completo en pgAdmin y ejecutarlo en esa base, o usar `psql -X -v ON_ERROR_STOP=1 -d NOMBRE_BASE_NUEVA -f shema_completo_corregido.sql` con tus parámetros de conexión.
3. El archivo usa una transacción: si falla en un editor SQL, ejecutar `ROLLBACK` antes de continuar. Rechaza bases cuyo esquema `public` ya tenga tablas, vistas o secuencias.

No contiene instrucciones para borrar tablas ni sustituir datos. Se validó en PostgreSQL 18.6; no se ejecutó en otras versiones.

### Convivencia con Flyway

El SQL no fabrica registros en `flyway_schema_history`. Por ello, **no ejecutar después V1 sobre la base creada manualmente**: los objetos ya existen.

Si se elige la instalación manual para conectar el backend, registrar una **baseline de Flyway en versión 15**, exclusivamente después de comprobar que esa base acaba de crearse con este archivo. Una forma es configurar `spring.flyway.baseline-on-migrate=true` y `spring.flyway.baseline-version=15` solo para ese primer arranque y retirar ambas opciones después. Las futuras migraciones mayores que V15 se aplicarán normalmente.

Nunca utilizar esa baseline para saltarse migraciones pendientes de una base antigua. En una base existente del proyecto, conservar su historial y actualizar con Flyway. No aplicar este script consolidado encima.

## Verificaciones realizadas

- Instancia temporal independiente de PostgreSQL 18.6; ninguna operación sobre la base de trabajo del usuario.
- Aplicación de V1 a V15, en orden numérico, sin errores.
- Ejecución del script consolidado en una segunda base vacía, sin errores.
- Comparación completa del DDL de ambas bases: tipos, tablas, columnas, valores por defecto, restricciones, comentarios e índices coinciden.
- Comparación de los cinco catálogos iniciales: coinciden, incluidos los identificadores.
- Cobertura de todas las columnas explícitas `@Column` en las 25 entidades `@Table`; las relaciones quedan respaldadas por las claves foráneas del DDL original.
- Segundo intento de instalación: rechazado por la protección de esquema ocupado.
- `gradlew.bat test`: `BUILD SUCCESSFUL`; Gradle reutilizó los resultados vigentes (`UP-TO-DATE`). No se modificó código Java.

Esta consolidación corrige el desfase de los scripts de referencia. No introduce reglas nuevas que diverjan de las migraciones: cualquier cambio futuro en restricciones o relaciones debe acompañarse de una nueva migración y su validación con el backend.
