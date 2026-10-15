# Correcciones — Consumo de la carpeta Siigo · `corporate-billing-service`

> Prompt para pegar en el agente. Corrige los hallazgos de la revisión del 5 de octubre de 2026 sobre el objetivo de la práctica: **recibir la factura y guardarla consumiendo la carpeta del servidor**. Igual que los sprints: no se cierra hasta que la compuerta esté en verde.

---

## Prompt (pegar en el agente)

**Correcciones al consumo de la carpeta Siigo.** Antes de tocar código lee `AGENTS.md`, `Docs/CONSUMO_FACTURAS_SERVIDOR.md` y `Docs/FRONTEND_RECEPCION.md`. El servicio lee la carpeta compartida donde Siigo Pyme deja PDF y archivos de datos, importa las facturas F1/F20 en la base y conserva una copia local de cada PDF. La lógica funciona en local, pero una revisión encontró los problemas de abajo. Corrígelos en el orden indicado.

### Reglas que no se negocian

- Código Java en inglés; tablas, columnas, mensajes al usuario y documentación en español (`AGENTS.md`).
- Capas verificadas por ArchUnit: `web → service → domain ← repository`. Los puertos van en `service/siigo`, las implementaciones en `infrastructure/siigo`. `ArchitectureLayerRulesTest` debe seguir en verde.
- **No edites migraciones aplicadas (V1–V15).** Lo nuevo va en `V16__…` (y siguientes si hacen falta).
- **Nunca muevas, renombres, modifiques ni borres archivos de la carpeta de origen.** Es de solo lectura para el servicio.
- No inventes datos fiscales (razón social, vencimiento, NIT) que el archivo no traiga.
- Fuera de alcance: pagos, pasarela, generación interna de facturas, órdenes, cartera. Solo toca esos módulos si una corrección lo exige, y explica por qué.
- No construyas un lector CSV/TXT: el formato definitivo de Siigo aún no está confirmado.

---

### Tarea 1 — Docker: montar bien la carpeta de origen y la de copias

**Problema.** En `docker-compose.yml` (línea 58) se monta `./storage/siigo-pdfs` en `/app/storage/siigo-pdfs` como **solo lectura**, pero esa es justo la carpeta donde el servicio escribe las copias de los PDF (`siigo.pdf.local-path`, por defecto `./storage/siigo-pdfs` con `WORKDIR /app`). Todas las copias fallan. Además, `SIIGO_CARPETA_COMPARTIDA_PATH` no se pasa al contenedor: el servicio busca `/app/storage/siigo_compartida`, que no existe, y cada ciclo termina FALLIDA. `STORAGE_SHARED_PDF_PATH` (línea 45) y `storage.siigo-pdf-path` en `application.yml` no los lee ningún código. `DOCKER.md` repite la confusión: presenta `siigo-pdfs` como la carpeta donde Siigo deposita los PDF.

**Corrección.**
1. `docker-compose.yml`:
   - Origen (solo lectura): `${LOCAL_SIIGO_CARPETA_PATH:-./storage/siigo_compartida}:/app/storage/siigo_compartida:ro`.
   - Copias locales (escribible y persistente): `${LOCAL_SIIGO_PDF_PATH:-./storage/siigo-pdfs}:/app/storage/siigo-pdfs:rw`.
   - Variables: `SIIGO_CARPETA_COMPARTIDA_PATH=/app/storage/siigo_compartida`, `SIIGO_PDF_LOCAL_PATH=/app/storage/siigo-pdfs`, y paso de `SIIGO_SYNC_CRON`, `SIIGO_ARCHIVO_EDAD_MINIMA`, `SIIGO_CONFIRMAR_LOTES_SALIENTES` y `SIIGO_CONSUMIR_CARTERA` con los mismos valores por defecto de `application.yml`.
   - Quita `STORAGE_SHARED_PDF_PATH`. Quita también `storage.siigo-pdf-path` de `application.yml` **solo si una búsqueda confirma que nada lo usa**. Haz la misma comprobación con `storage.files-path` y `STORAGE_SHARED_FILES_PATH`, pero no los borres si algo los lee.
2. `Dockerfile`: crea también `/app/storage/siigo_compartida` con dueño `spring`.
3. Al iniciar, registra en el log las rutas absolutas resueltas de origen y de copias. Si la carpeta de copias no es escribible, el ciclo debe fallar una vez con un mensaje claro (`Carpeta local de PDF no escribible: <ruta>`), no marcar cada PDF como "error de lectura o almacenamiento".
4. `.env.example`: agrega `LOCAL_SIIGO_CARPETA_PATH` (con un comentario que diga que en producción es la ruta UNC o el punto de montaje real) y corrige el comentario de `LOCAL_SIIGO_PDF_PATH`: son las copias que conserva el servicio, no la carpeta de Siigo.
5. `DOCKER.md`: corrige el árbol de carpetas, la tabla de variables, los comandos `mkdir`/`New-Item` (deben crear `siigo_compartida`) y la sección de solución de problemas de permisos.

**Verificación.** `docker compose config` debe mostrar las dos monturas con el modo correcto. Si Docker está disponible, levanta el stack, copia un archivo a la carpeta de origen y dispara `POST /api/v1/sincronizacion/ahora`. Si no lo está, dilo explícitamente en el resumen final.

---

### Tarea 2 — Registrar cada PDF recibido y conservar los que aún no tienen factura

**Problema.** Si un PDF llega antes que su archivo de datos, o la coincidencia es ambigua, hoy solo queda en la carpeta de origen. Según la reunión del 28/09, Siigo deja los PDF en una **carpeta de temporales**: si la limpian antes de que llegue el archivo de datos, el PDF se pierde.

**Corrección.**
1. Migración `V16__pdf_recibido.sql` con la tabla `pdf_recibido`:
   - `id UUID PK`, `nombre_original VARCHAR(255) NOT NULL`, `sha256 VARCHAR(64) NOT NULL UNIQUE`, `tamano_bytes BIGINT NOT NULL`, `modificado_en_origen TIMESTAMPTZ NOT NULL`, `ruta_local VARCHAR(500) NOT NULL`, `estado VARCHAR(20) NOT NULL` (`PENDIENTE` | `ASOCIADO`), `factura_id UUID NULL REFERENCES factura(id)`, `motivo VARCHAR(500)`, `intentos INTEGER NOT NULL DEFAULT 0`, `recibido_en TIMESTAMPTZ NOT NULL`, `ultimo_intento_en TIMESTAMPTZ`, `asociado_en TIMESTAMPTZ`.
   - Índices por `estado` y por `(nombre_original, tamano_bytes, modificado_en_origen)`.
   - Entidad en `domain/model` (nombre en inglés, p. ej. `ReceivedPdf`) y su repositorio.
2. Todo PDF válido que se lea de la carpeta (las validaciones de hoy: tamaño ≤ 20 MB, sin cambios durante la lectura, PDFBox con al menos una página) queda registrado en `pdf_recibido`:
   - **Si se asocia:** se copia como hoy a `<local-path>/<facturaId>/<sha256>.pdf`, con el histórico de `factura_pdf_historico` si reemplaza otro PDF, y la fila queda `ASOCIADO` con `factura_id`.
   - **Si no se asocia:** se copia a `<local-path>/_pendientes/<sha256>.pdf` con la misma escritura atómica (temporal + `move`), y la fila queda `PENDIENTE` con el motivo (`Sin factura con ese tipo y consecutivo`, `Coincidencia ambigua`, etc.). Cada ciclo incrementa `intentos` y actualiza `ultimo_intento_en`.
3. En cada ciclo, después de importar los datos, reintenta asociar las filas `PENDIENTE` **usando la copia local**, aunque el original ya no exista en la carpeta. Aplica las mismas reglas que hoy: convención `F001`/`F020` sobre `nombre_original`, luego CUFE y luego número con NIT coincidente. Un NIT solo nunca basta.
4. No borres la copia pendiente dentro de la transacción. Si decides limpiarla tras asociarla, hazlo después del commit.
5. Endpoint `GET /api/v1/siigo/pdf-pendientes` (paginado, `ROL_ANALISTA` y `ROL_ADMINISTRADOR`): nombre original, recibido en, intentos, motivo. Agrega el número de pendientes a `GET /api/v1/facturas/recibidas/resumen` como campo nuevo, sin quitar ni renombrar los campos existentes.

---

### Tarea 3 — Dejar de ignorar en silencio los `.txt`, `.csv` y `.dat`

**Problema.** `OfficialInvoiceFolderSyncService.java` (línea 25) solo toma los `.json` de `listConfirmationFlatFiles()`. Los `.txt`, `.csv` y `.dat` se descartan sin auditoría. En la reunión se habla de "archivos planos": si Siigo deja un `.txt`, nadie se entera.

**Corrección.**
- Registra esos archivos en `historial_recepcion_archivo` con estado `OMITIDO` y el detalle `Formato no soportado todavía: requiere un adaptador para el diseño que entregue Siigo`. Inclúyelos en las advertencias del ciclo para que quede `CON_ADVERTENCIAS`, no `FALLIDA`.
- `BatchService` (línea 192) también lee esos archivos como confirmaciones de lotes salientes cuando `siigo.sync.confirmar-lotes-salientes=true`. En ese caso el mensaje no debe decir que el archivo es inválido; debe indicar que se dejó al módulo de lotes.
- Se registran una sola vez por contenido (ver la tarea 4), no cada hora.

---

### Tarea 4 — No reprocesar todo cada hora

**Problema.** Los originales nunca se mueven, así que cada hora se reprocesa la carpeta entera:
- Se vuelve a parsear cada Excel con POI (el de ejemplo trae 25 MB de XML).
- Se escribe una fila de auditoría por archivo y por hora.
- Por cada PDF se ejecuta `repositorioFactura.findAll()` (líneas 185 y 211 de `SiigoPdfImporterService.java`), que carga todas las facturas con sus ítems `EAGER`. Con unas 1.200 facturas al mes, el costo crece de forma cuadrática.
- Además, `processSharedFolderPdfs()` procesa todos los PDF en una sola transacción: un error de base de datos en uno marca rollback y se pierden las asociaciones de todo el ciclo.

**Corrección.**
1. **Archivos de datos (origen `CARPETA`):** calcula el SHA-256 antes de parsear. Si `historial_recepcion_archivo` ya tiene ese hash con estado `IMPORTADO` u `OMITIDO`, sáltalo sin parsearlo y sin escribir auditoría. Los `RECHAZADO` se siguen reintentando cada ciclo (comportamiento documentado), pero solo se escribe una fila nueva si cambia el estado o el detalle respecto de la última fila de ese hash. Agrega un índice sobre `historial_recepcion_archivo(sha256)` en una migración nueva. La carga manual (`POST /api/v1/siigo/facturas/importar`) no cambia: siempre valida y audita.
2. **PDF ya asociados:** si `pdf_recibido` ya tiene una fila `ASOCIADO` con el mismo `nombre_original`, `tamano_bytes` y `modificado_en_origen`, salta el archivo sin leerlo.
3. **Búsqueda de la factura sin `findAll()`:**
   - Primero `findByExternalDocumentKey(tipo + ":" + consecutivo)`, que ya tiene índice único.
   - Como respaldo para facturas internas, usa una consulta acotada (tipo, estado distinto de `ANULADA` y número que termine en el consecutivo) y aplica `normalizedNumber` en memoria sobre ese resultado pequeño.
   - Haz lo mismo en la búsqueda por metadatos del PDF.
   - Conserva la regla de hoy: si hay más de una coincidencia, no se asocia.
4. **Una transacción por PDF**, igual que los archivos de datos. Ojo: `@Transactional` no aplica en llamadas internas del mismo bean. Usa un bean separado o `TransactionTemplate`.
5. El contador de "facturas verificadas" ya no incluirá los archivos saltados. Actualiza la explicación en `CONSUMO_FACTURAS_SERVIDOR.md` y deja en el log del ciclo cuántos archivos se saltaron por no tener cambios.

---

### Tarea 5 — Rechazo parcial de un archivo de datos (DECISIÓN: confirmar antes de aplicar)

> Esta tarea cambia un comportamiento documentado. Si el equipo prefiere mantener "todo o nada" por archivo, **omítela** y deja documentado el motivo.

**Problema.** Una sola factura inválida o modificada rechaza el archivo completo (las 1.209 facturas del movimiento de ejemplo). Si Siigo reexporta el movimiento del mes con un documento corregido, las facturas nuevas de ese archivo tampoco entran hasta que alguien lo revise.

**Corrección.**
- Importa cada documento válido de forma independiente. Un documento inválido o con cambios frente al ya guardado se rechaza solo, **sin sobrescribir nunca la factura existente** (regla vigente).
- Si hubo rechazos, el archivo queda auditado como `PARCIAL`, con la lista de claves rechazadas y el motivo (truncada a 1.500 caracteres). Un `PARCIAL` se reintenta como un `RECHAZADO`.
- No cambies el contrato de respuesta de `POST /api/v1/siigo/facturas/importar`. Los rechazos parciales se consultan en `GET /api/v1/siigo/facturas/historial`. Anota en `FRONTEND_RECEPCION.md` que el panel debe mostrar la etiqueta del estado `PARCIAL`.

---

### Tarea 6 — Descarga del PDF

En `InvoiceController.getInvoicePdf` (líneas 204–230):
- Usa `Content-Disposition: attachment` con nombre `<tipo>-<consecutivo>.pdf` (p. ej. `F1-51993.pdf`), y el id solo si falta el número. Constrúyelo con `ContentDisposition` para escapar bien el nombre. Hoy se envía el hash como nombre.
- Mantén `Content-Type: application/pdf`: el panel comprueba el tipo del blob para la vista previa (`invoice-graphic.component.ts`).
- Si la factura tiene `ruta_pdf` pero el archivo no existe o no se puede leer, no lo silencies con `catch (Exception ignored)` ni respondas como si nunca hubiera llegado. Regístralo en el log y responde con un error distinguible del 404 de "PDF aún no recibido" (`FRONTEND_RECEPCION.md` ya promete que la descarga reporta ese error).

---

### Tarea 7 — Prueba de punta a punta con el formato real de Siigo

**Problema.** Nunca se ha probado el recorrido completo carpeta → base de datos → copia del PDF → descarga con el formato real. La prueba de PDF usa una factura simulada. Además, los PDF de ejemplo (F1 51993 y F20 287) no están en el movimiento de ejemplo, que llega de F1 50776 a 51985.

**Prueba de integración nueva.** Sigue el patrón de `OfficialInvoiceImportIntegrationTest`: `@SpringBootTest` con `@ActiveProfiles("postgres")`, que usa la base PostgreSQL local.
1. Carpeta de origen y carpeta de copias en `@TempDir`, inyectadas con `@DynamicPropertySource` (`siigo.carpeta-compartida.path`, `siigo.pdf.local-path`, `siigo.sync.antiguedad-minima-segundos=0`).
2. **Fixture con el diseño real, sin chocar con datos reales:**
   - Abre `Docs/archivosplanos/movimientocontableFACSEP.xlsx` con POI y conserva el encabezado y solo las filas del documento F1 51753.
   - Reemplaza el consecutivo por uno aleatorio único y el NIT por uno `TEST-…`, y guárdalo en la carpeta de origen.
   - La base local puede tener ya importado el movimiento real: **no uses consecutivos reales ni borres datos que la prueba no creó.**
3. Pon en la carpeta de origen:
   - un PDF generado con PDFBox y nombrado con la convención para ese consecutivo (`F001` + consecutivo con ceros a 11 dígitos + `0000.PDF`);
   - un PDF de otro consecutivo inexistente (debe quedar pendiente);
   - una copia de `saldos_de_cartera.xlsx` (debe quedar `OMITIDO`);
   - un `notas.txt` (debe quedar `OMITIDO` por formato no soportado).
4. **Ciclo 1** (`triggerManualSync()`):
   - La factura existe con el NIT, el total y los ítems del fixture.
   - `ruta_pdf` apunta dentro de la carpeta de copias y el contenido es idéntico byte a byte al original.
   - Los originales siguen en su sitio, sin cambios.
   - El PDF huérfano queda `PENDIENTE` y tiene copia en `_pendientes`.
   - El historial tiene las filas esperadas.
5. **Ciclo 2** sin cambios: ni facturas duplicadas, ni filas nuevas de historial, ni reasociación de PDF.
6. **Ciclo 3:** borra el PDF huérfano de la carpeta de origen y deja un JSON con la factura de ese consecutivo. El PDF se asocia desde la copia local y la fila pasa a `ASOCIADO`.
7. **Descarga** con MockMvc, siguiendo el patrón de seguridad de las pruebas de controlador existentes: `GET /api/v1/facturas/{id}/pdf` devuelve los mismos bytes con `attachment` y `F1-<consecutivo>.pdf`.
8. **Limpieza** en `finally` con `PROPAGATION_REQUIRES_NEW`, como en `rejectionIsAuditedWithoutPersistingAnInvalidInvoice`: borra solo lo que creó la prueba (facturas, cliente `TEST-…`, historial y filas de `pdf_recibido` del hash generado).

Añade también pruebas unitarias para: carpeta de copias no escribible (tarea 1), PDF pendiente asociado sin el original (tarea 2), archivo saltado por hash (tarea 4), descarga con archivo ilegible (tarea 6) y, si se aplica, rechazo parcial (tarea 5).

---

### Tarea 8 — Documentación

- `Docs/CONSUMO_FACTURAS_SERVIDOR.md`:
  - monturas de Docker y variables nuevas;
  - registro de PDF y pendientes;
  - archivos omitidos por formato;
  - salto por hash y nuevo significado del contador;
  - nombre de descarga;
  - estado `PARCIAL` (si se aplicó la tarea 5).
- En "Validación y pendientes externos", deja explícito que la carpeta de ejemplo de Siigo (`Docs/Ejemplo carpeta siggo`) solo trae PDF y `saldos_de_cartera.xlsx`. Sin el movimiento contable (o un JSON) no se crea ninguna factura, y hay que confirmar con la empresa qué archivo de datos dejará Siigo en la carpeta.
- Actualiza también `DOCKER.md`, `.env.example` y `Docs/FRONTEND_RECEPCION.md` (endpoint de pendientes, campo nuevo del resumen, estado `PARCIAL`).

---

### Compuerta de salida

- `.\gradlew.bat test` en verde, incluido ArchUnit. Las pruebas de integración usan PostgreSQL local (perfil `postgres`). Si no está disponible, dilo; no des la tarea por cerrada.
- Ninguna migración V1–V15 modificada. Las nuevas aplican sobre una base que ya tiene V15.
- Ningún archivo de la carpeta de origen se modifica en ninguna prueba.
- Resumen final con: archivos cambiados por tarea, migraciones nuevas, decisión tomada en la tarea 5 y lo que **no** se pudo verificar (Docker real, carpeta real del servidor).

---

## Fuera del código (lo resuelve el equipo con la empresa)

- Ruta real de la carpeta y permisos de lectura para la cuenta del servicio.
- **Qué archivo de datos dejará Siigo en la carpeta**: movimiento contable Excel con el diseño actual, JSON u otro. Si es otro formato (XML, CSV, TXT), se necesita un adaptador nuevo.
- Si la carpeta de temporales de Siigo se limpia y cada cuánto, para dimensionar el registro de pendientes.
