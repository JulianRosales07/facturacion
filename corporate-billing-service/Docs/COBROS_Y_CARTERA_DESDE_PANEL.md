# Cartera y cobros desde el panel

## Alcance confirmado el 15 de octubre de 2026

El microservicio debe trabajar con información financiera real. La pasarela web se conserva simulada, por instrucción expresa del usuario; no se contrató ni se conectó un proveedor bancario en este cambio. El modo de prueba y los datos de producción deben permanecer separados.

Los saldos negativos del Excel son saldos a favor del cliente. Se conservan separados de las deudas, sin compensación automática entre documentos.

## Cargar el Excel

En «Facturas recibidas», un usuario con `IMPORTS_EXECUTE` indica la fecha y hora de generación del corte, en hora de Colombia, y selecciona «Importar archivo». El Excel de ejemplo `Docs/server/saldos_de_cartera.xlsx` no contiene esa marca de generación: `FECHA DOCUMENTO` es la fecha de cada factura y no se utiliza para fechar el corte. La hora indicada por el operador queda en la auditoría junto al hash SHA-256. No debe usarse la hora de carga para hacer pasar un extracto antiguo como nuevo.

El panel envía `multipart/form-data` a `POST /api/v1/siigo/facturas/importar-detallado`, campos `archivo` y `fechaCorte` (instante ISO-8601, por ejemplo `2026-10-14T15:00:00Z`). Recibe `invoiceIds`, `created`, `updated`, `repeated` e `importedBalance`. Este último es la suma algebraica del saldo de los documentos leídos, incluidos saldos a favor; no es la deuda bruta cobrable.

El endpoint anterior `/importar` conserva su respuesta de IDs y admite el mismo parámetro `fechaCorte`. Un archivo de cartera sin fecha de corte se rechaza. Los archivos de movimiento contable continúan aportando detalle oficial mediante el contrato existente.

Las carpetas automáticas no deben aplicar el Excel de ejemplo sin una marca fiable de generación. El recorrido antiguo de conciliación se bloquea cuando falta esa información, en lugar de inferirla de una factura. Para este formato se utiliza la carga del panel con corte declarado. Automatizarlo exige acordar cómo Siigo entrega la marca por archivo.

## Qué se guarda y qué puede cobrarse

- La identidad externa sigue siendo tipo/consecutivo, protegida por un índice único. La importación bloquea clientes y documentos al modificarlos.
- Una fila de cartera trae un saldo, no el total original ni el IVA. Una obligación nueva queda con detalle pendiente, importes oficiales sin completar y saldo separado; no es cobrable todavía.
- El detalle oficial se completa desde movimiento contable o PDF consistente. Una actualización posterior de cartera conserva total original, base e IVA.
- Un saldo cero explícito cierra la deuda. Una fila ausente no se considera un pago. Un saldo negativo se conserva como saldo a favor y no se convierte en una factura pagada automáticamente.
- Cortes anteriores y saldos diferentes para el mismo instante se rechazan. Las fechas inválidas, cuotas contradictorias y monedas distintas de COP requieren conciliación; no se fabrican fechas ni conversiones.
- Simulación, checkout, registro manual y confirmación directa verifican que el documento sea cobrable. No se liquidan retenciones reales sobre bases estimadas de saldos parciales: ese caso exige bases conciliadas.
- Un pago local no cambia el importe ni el instante informados por Siigo. Su cruce queda separado hasta que un corte posterior lo refleje. Un corte sin cambios conserva el descuento local; un corte que no concilia ese cruce se rechaza.
- Las confirmaciones exigen referencia e importe del proveedor. Una transacción repetida con datos distintos produce conflicto. Los callbacks de checkout aprobados deben coincidir con su importe exacto.

Las vistas agrupadas y el PDF agrupado son estados de cuenta. Se retiraron el IVA fijo calculado al 19 %, los códigos de producto de ejemplo y los datos que hacían pasar el resumen por una factura oficial. Los conceptos y tributos se consultan por documento. `valor_pagado` agregado representa valor saldado/cruzado, no acredita por sí solo efectivo ingresado: también pueden existir abonos externos o ajustes.

## Configuración y despliegue

La autenticación REST y WebSocket queda activa por defecto. `AUTH_ENABLED=false` solo se admite en perfiles `local` o `test`, y se rechaza al combinarlos con `production`, `prod` o `docker`. Las confirmaciones `/pagos/confirmar` y `/pagos/webhook` exigen rol técnico de pasarela o sistema. El navegador de un cliente no debe recibir esas credenciales en un entorno real.

Para desarrollo local con la pasarela simulada: `SPRING_PROFILES_ACTIVE=postgres,local`, sobre una base de prueba. En producción se mantiene autenticación activa, emisor/audiencia/JWKS correctos y usuarios con permisos mínimos. Las contraseñas de base de datos se suministran mediante `DB_PASSWORD` para el perfil PostgreSQL o `SPRING_DATASOURCE_PASSWORD` para Docker; se retiraron los valores literales predeterminados. Si la credencial anterior era real, debe rotarse en su sistema de origen.

Se añade `V20261015__cruces_locales_pendientes_de_siigo.sql`, con importe de cruce local pendiente y fecha del último cruce. No se editaron migraciones existentes. Hibernate valida el esquema y Flyway valida las migraciones. Primero se prueba la migración sobre una copia y se conserva un respaldo recuperable.

La migración inicia los cruces nuevos en cero. **No reconstruye saldos históricos alterados por versiones anteriores ni distingue pagos simulados ya guardados.** Esos datos deben conciliarse contra Siigo y banco antes de habilitar una base existente para cobros reales. No se eliminan ni se convierten pagos históricos automáticamente.

No se desplegó en producción, no se cobraron fondos y no se certificó el entorno de la empresa. Antes de habilitarlo deben verificarse la conciliación independiente del Excel real, los usuarios reales, la restauración del respaldo y la separación de la pasarela simulada. Si se activa recaudo bancario en línea, se necesita una integración de proveedor con confirmación desde servidor.

## Verificación

La ejecución final de `gradlew.bat test` terminó con **BUILD SUCCESSFUL: 431 pruebas, 0 fallos, 0 errores y 0 omitidas**, sobre PostgreSQL 16 temporal, separado de la configuración habitual. Incluyó migraciones, carga multipart del panel, lectura del archivo real de ejemplo, abonos y ceros, conservación del documento original, pagos repetidos, discrepancias, concurrencia y permisos de confirmación. El contenedor de pruebas se retiró al terminar.

El panel compiló para producción y su suite terminó con **106 pruebas aprobadas en 43 archivos**. La compilación informa una advertencia de optimización por CommonJS en `@stomp/stompjs`. No se modificó el proyecto de la pasarela simulada. Las pruebas no sustituyen la conciliación contable ni una prueba en el entorno desplegado.

La paginación por NIT aún agrupa resultados en memoria y el sincronizador usa coordinación local. Son mejoras pendientes de capacidad: antes de operar múltiples réplicas o históricos grandes deben paginarse los clientes en PostgreSQL y coordinarse los trabajos entre instancias.
