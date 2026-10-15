# Manual de Contenerización y Despliegue con Docker
## Microservicio `corporate-billing-service` — Sistemas Palacios

Este documento es la guía técnica y operativa completa para construir, configurar, ejecutar, verificar y mantener el microservicio `corporate-billing-service` junto con su base de datos PostgreSQL mediante **Docker** y **Docker Compose**.

---

## 1. Requisitos Previos

Para ejecutar el microservicio en contenedores necesitas:
- **Docker Desktop**: Versión `4.25` o superior (Recomendado para Windows 10/11 con WSL2 o macOS) o **Docker Engine** `24.0+` en Linux.
- **Docker Compose**: Versión `v2.20` o superior (ya viene integrado por defecto dentro de Docker Desktop con el comando `docker compose`).
- **WSL 2 (Windows Subsystem for Linux)**: En Windows, asegura tener instalado y actualizado el componente WSL 2 (`wsl --update` en PowerShell).

### Verificación de requisitos en tu terminal (PowerShell, CMD o Bash):
```bash
# 1. Verificar versión de Docker Engine
docker --version
# Salida esperada: Docker version 24.x.x o superior

# 2. Verificar versión de Docker Compose v2
docker compose version
# Salida esperada: Docker Compose version v2.x.x
```

---

## 2. Estructura de Archivos de Contenerización

Los siguientes archivos componen la arquitectura de empaquetado y orquestación del microservicio:

```
corporate-billing-service/
├── Dockerfile                    # Construcción multi-stage: JDK 21 (build) + JRE 21 (runtime seguro no-root)
├── .dockerignore                 # Exclusión de binarios locales, artefactos de build y secretos
├── docker-compose.yml            # Orquestación base: servicios app y db en red aislada
├── docker-compose.override.yml   # Sobrescrituras para desarrollo local (puerto 5432 expuesto, logs debug)
├── .env.example                  # Plantilla documentada de variables de entorno (sin credenciales reales)
├── storage/                      # Carpetas locales para volúmenes compartidos (bind mounts)
│   ├── siigo_compartida/         # Carpeta de origen donde Siigo deposita PDF y archivos (montada en solo lectura :ro)
│   ├── siigo-pdfs/               # Carpeta local persistente donde el servicio guarda copias de PDF (escribible :rw)
│   └── files/                    # Carpeta para archivos de importación de órdenes y exportación plana
└── DOCKER.md                     # Este manual de referencia operativa
```

---

## 3. Configuración Inicial (`.env`)

El microservicio utiliza variables de entorno para desacoplar las credenciales y rutas del código fuente.

### Paso 1: Crear el archivo `.env` a partir de la plantilla

En **PowerShell (Windows)**:
```powershell
Copy-Item .env.example .env
```

En **Bash / Linux / macOS / Git Bash**:
```bash
cp .env.example .env
```

> [!IMPORTANT]
> El archivo `.env` se encuentra explícitamente ignorado en `.gitignore`. **Nunca subas credenciales reales o archivos `.env` a ningún repositorio de control de versiones.**

### Paso 2: Significado de cada variable de configuración

| Variable | Descripción | Valor por Defecto / Ejemplo |
|---|---|---|
| `POSTGRES_DB` | Nombre de la base de datos de facturación | `facturacion` |
| `POSTGRES_USER` | Usuario administrador de PostgreSQL | `billing_user` |
| `POSTGRES_PASSWORD` | Contraseña segura para PostgreSQL | `billing_password_secreta_ejemplo` |
| `POSTGRES_PORT` | Puerto de PostgreSQL publicado en el host (solo desarrollo) | `5432` |
| `APP_PORT` | Puerto HTTP expuesto por el microservicio en el host | `8080` |
| `SPRING_PROFILES_ACTIVE` | Perfil de configuración de Spring Boot | `docker` |
| `JAVA_OPTS` | Parámetros de la JVM (gestión de memoria en contenedor) | `-XX:MaxRAMPercentage=75.0` |
| `ORDENES_BASE_URL` | URL base para consumir los endpoints del sistema de Órdenes | `http://host.docker.internal:8081` |
| `ORDENES_API_KEY` | Token o clave de autenticación hacia Órdenes | `token_ordenes_desarrollo_ejemplo` |
| `ORDENES_TIMEOUT_MS` | Tiempo máximo de espera en milisegundos para peticiones HTTP | `5000` |
| `ORDENES_REINTENTOS` | Número de reintentos automáticos ante fallas transitorias | `3` |
| `LOCAL_SIIGO_CARPETA_PATH` | Ruta local en el host donde Siigo deposita PDF y archivos de datos (origen :ro) | `./storage/siigo_compartida` |
| `LOCAL_SIIGO_PDF_PATH` | Ruta local persistente donde el servicio guarda copias de PDF recibidos (:rw) | `./storage/siigo-pdfs` |
| `LOCAL_FILES_PATH` | Ruta local en el host para archivos de importación/exportación | `./storage/files` |

---

## 4. Paso a Paso: Cómo Agregar y Gestionar el Proyecto en Docker Desktop

Esta sección detalla cómo abrir, levantar, monitorear e interactuar visualmente con el proyecto usando la interfaz gráfica de **Docker Desktop**.

### Paso 4.1: Iniciar y verificar Docker Desktop
1. Abre la aplicación **Docker Desktop** desde el menú de inicio de Windows o Spotlight en macOS.
2. Espera unos segundos hasta que el ícono de la ballena en la bandeja del sistema (esquina inferior derecha en Windows) deje de animarse y el indicador en la esquina inferior izquierda de la ventana de Docker Desktop cambie a color **verde** con el mensaje:
   ```text
   Engine running
   ```
3. *(Opcional - Recomendado en Windows)*: Ve a **Settings (icono de engranaje ⚙️)**:
   - En **General**: Verifica que esté marcada la opción **Use the WSL 2 based engine**.
   - En **Resources > Advanced**: Si tu equipo lo permite, asigna al menos 4 GB de memoria RAM para un compilado Gradle ágil.

---

### Paso 4.2: Asegurar la estructura previa de carpetas
Asegúrate de tener creadas las carpetas locales requeridas para los volúmenes en `storage/`:
```powershell
# En PowerShell (Windows)
New-Item -ItemType Directory -Force -Path .\storage\siigo_compartida, .\storage\siigo-pdfs, .\storage\files
```
```bash
# En Bash / Git Bash
mkdir -p storage/siigo_compartida storage/siigo-pdfs storage/files
```

---

### Paso 4.3: Agregar y levantar el stack por primera vez
Para registrar el proyecto en Docker Desktop, el orquestador necesita leer el archivo `docker-compose.yml`:

1. Abre una terminal (PowerShell, Windows Terminal o la terminal integrada de VS Code) posicionada exactamente en la carpeta del proyecto:
   ```powershell
   cd c:\Users\Usuario\Documents\corporate-billing-service\corporate-billing-service
   ```
2. Ejecuta el comando de construcción y arranque en segundo plano:
   ```bash
   docker compose up -d --build
   ```
3. Docker descargará la imagen base de PostgreSQL, compilará el código Java mediante el multi-stage Dockerfile y arrancará los dos contenedores.

---

### Paso 4.4: Visualización del Stack en Docker Desktop (GUI)
Abre la ventana principal de **Docker Desktop** y dirígete a la pestaña **Containers** en el menú lateral izquierdo:

1. **Grupo de Contenedores (Compose Stack):**
   - Verás un grupo llamado **`corporate-billing-service`** con un badge verde **`RUNNING`**.
   - Haz clic en la flecha desplegable `>` a la izquierda del grupo para expandir y ver los 2 servicios individuales:
     - 🟢 **`corporate-billing-service`** (Microservicio Java Spring Boot 4).
     - 🟢 **`corporate-billing-db`** (Base de datos PostgreSQL 16 Alpine).
2. **Estado de Salud (Healthcheck):**
   - Ambos contenedores mostrarán una etiqueta verde `healthy`, indicando que Postgres está listo para conexiones y que Spring Boot pasó la prueba de Actuator (`/actuator/health`).

---

### Paso 4.5: Acceso rápido desde Docker Desktop (Open in Browser)
En la lista de contenedores, observa la columna **Port(s)**:
- Al lado del contenedor `corporate-billing-service` verás un enlace interactivo: `8080:8080`.
- Haz clic directo sobre él o abre en tu navegador:
  - **Healthcheck Actuator:** [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
  - Respuesta esperada: `{"status":"UP", ...}`.
- El puerto `5432:5432` de `corporate-billing-db` te permite conectarte desde cualquier cliente SQL local como **DBeaver**, **pgAdmin** o **DataGrip** usando:
  - Host: `localhost` | Puerto: `5432` | DB: `facturacion` | User: `billing_user` | Pass: `billing_password_secreta_ejemplo`

---

### Paso 4.6: Monitoreo de Logs en Vivo desde la Interfaz Gráfica
1. Haz clic directamente sobre el nombre del contenedor **`corporate-billing-service`** dentro de Docker Desktop.
2. Se abrirá la pestaña **Logs**:
   - Verás la salida de consola en tiempo real (colorizada).
   - **Buscador integrado:** Usa la barra superior de búsqueda para filtrar eventos clave (ej. escribe `Flyway`, `HikariPool` o `Started CorporateBillingServiceApplication`).
   - Puedes activar o pausar el **Auto-scroll** o descargar los logs con el botón de exportación.

---

### Paso 4.7: Consola Interactiva (Terminal / Exec en la GUI)
No necesitas abrir terminales del sistema para interactuar con el interior de los contenedores:
1. Haz clic en el contenedor (por ejemplo, **`corporate-billing-db`**).
2. Ve a la pestaña **Exec** (o el botón de terminal `>_`).
3. Aparecerá una línea de comandos directa dentro del contenedor. Por ejemplo, escribe:
   ```bash
   psql -U billing_user -d facturacion
   ```
   Y luego consulta las tablas de la base de datos:
   ```sql
   \dt
   SELECT * FROM flyway_schema_history;
   ```
4. También puedes usar la pestaña **Exec** en `corporate-billing-service` para verificar variables o conectividad:
   ```bash
   env | grep SPRING
   ```

---

### Paso 4.8: Explorador de Archivos del Contenedor (Pestaña "Files")
1. En la vista del contenedor `corporate-billing-service`, haz clic en la pestaña **Files**.
2. Puedes navegar el árbol de directorios interno:
   - `/app/app.jar`: El ejecutable generado.
   - `/app/storage/siigo-pdfs/`: La carpeta montada donde se visualizan los PDFs colocados en el host.
   - `/app/storage/files/`: Archivos planos generados o por procesar.

---

### Paso 4.9: Métricas en Tiempo Real (Pestaña "Stats")
Haz clic en la pestaña **Stats** de cualquiera de los dos contenedores para monitorear:
- **Uso de CPU (%):** Consumo del hilo del proceso Java o motor SQL.
- **Uso de Memoria (RAM):** Cuántos MB está consumiendo el Heap de la JVM respecto al límite asignado.
- **Network I/O & Disk I/O:** Tráfico de red y operaciones de lectura/escritura de disco.

---

### Paso 4.10: Gestión de Volúmenes e Imágenes en Docker Desktop
- **Pestaña Volumes (Menú lateral izquierdo):**
  - Ubica el volumen persistente **`corporate_billing_postgres_data`**.
  - Este volumen almacena físicamente las tablas y registros de PostgreSQL para que los datos sobrevivan aunque detengas los contenedores.
  - Puedes inspeccionar su tamaño y ubicación física.
- **Pestaña Images:**
  - Verás listada la imagen construida **`corporate-billing-service-app`** (etiquetada como `latest`) y la imagen oficial descargada **`postgres:16-alpine`**.

---

### Paso 4.11: Botones de Control de Ciclo de Vida (GUI)
En la parte superior derecha de cada contenedor o sobre la fila del stack `corporate-billing-service` en Docker Desktop dispones de los siguientes botones rápidos:
- ▶ **Start**: Inicia los contenedores si están detenidos.
- ⏸ **Pause / Unpause**: Congela temporalmente la ejecución de los procesos sin liberar memoria.
- ⏹ **Stop**: Detiene los contenedores de forma ordenada (graceful shutdown).
- ↺ **Restart**: Reinicia el contenedor (ideal si modificaste un archivo de configuración).
- 🗑️ **Delete**: Elimina el contenedor. *(Los datos de la BD no se pierden porque están a salvo en el volumen persistente)*.

---

## 5. Levantar y Controlar el Entorno desde Terminal (CLI)

Si prefieres la terminal o deseas automatizar tareas:

### Comando para iniciar el stack completo:
```bash
# Construir imágenes y levantar contenedores en segundo plano (detached mode)
docker compose up -d --build
```

### Lo que verás en pantalla:
```text
[+] Building 14.2s (16/16) FINISHED
 => [internal] load build definition from Dockerfile
 => [internal] load .dockerignore
 => [build 1/7] FROM docker.io/library/eclipse-temurin:21-jdk...
 => [build 4/7] RUN ./gradlew dependencies --no-daemon
 => [build 6/7] RUN ./gradlew clean bootJar -x test --no-daemon
 => [runtime 4/5] COPY --from=build /workspace/build/libs/*.jar /app/app.jar
[+] Running 3/3
 ✔ Network billing-network               Created
 ✔ Container corporate-billing-db        Healthy
 ✔ Container corporate-billing-service   Started
```

---

## 6. Verificación del Despliegue

Sigue este checklist paso a paso para asegurar que la base de datos y la aplicación arrancaron con éxito:

### 6.1. Estado de los contenedores
```bash
# Listar contenedores y su estado de salud
docker compose ps
```
**Salida esperada:**
Tanto `corporate-billing-db` como `corporate-billing-service` deben figurar en estado `Up` y `(healthy)`.

```text
NAME                        IMAGE                         COMMAND                  SERVICE   STATUS                    PORTS
corporate-billing-db        postgres:16-alpine            "docker-entrypoint.s…"   db        Up (healthy)              0.0.0.0:5432->5432/tcp
corporate-billing-service   corporate-billing-service-app "sh -c 'exec java $J…"   app       Up (healthy)              0.0.0.0:8080->8080/tcp
```

### 6.2. Verificación del Endpoint de Salud (Actuator)
```bash
# Consultar el estado de salud expuesto por Spring Boot Actuator
curl -i http://localhost:8080/actuator/health
```
**Salida esperada:**
```http
HTTP/1.1 200 OK
Content-Type: application/vnd.spring-boot.actuator.v3+json

{"status":"UP","components":{"db":{"status":"UP","details":{"database":"PostgreSQL","validationQuery":"isValid()"}},"diskSpace":{"status":"UP"},"ping":{"status":"UP"}}}
```

### 6.3. Verificación de Migraciones Flyway en Logs
```bash
# Filtrar los logs de inicio para confirmar que Flyway migró el esquema inicial
docker compose logs app | grep -i flyway
```
**Salida esperada:**
```text
INFO ... o.f.c.i.database.base.BaseDatabaseType : Database: jdbc:postgresql://db:5432/facturacion (PostgreSQL 16.x)
INFO ... o.f.core.internal.command.DbMigrate     : Current version of schema "public": << Empty Schema >>
INFO ... o.f.core.internal.command.DbMigrate     : Migrating schema "public" to version "1 - esquema inicial"
INFO ... o.f.core.internal.command.DbMigrate     : Successfully applied 1 migration to schema "public"
```

### 6.4. Verificación de Tablas en PostgreSQL
```bash
# Conectarse a Postgres dentro del contenedor y listar las tablas creadas
docker compose exec db psql -U billing_user -d facturacion -c "\dt"
```
**Salida esperada:**
Listado con todas las tablas del negocio creadas por `V1__esquema_inicial.sql`:
- `cliente_corporativo`
- `sede_suscripcion`
- `periodo_facturacion`
- `novedad_orden`
- `cuota_instalacion`
- `lote_exportacion`
- `factura`
- `item_facturable`
- `vigencia_concepto`
- `concepto_tributario`
- `perfil_tributario_cliente`
- `mapeo_cuenta_contable`

---

## 7. Operación Diaria

### Ver logs en tiempo real
```bash
# Ver logs continuos de la aplicación
docker compose logs -f app

# Ver logs continuos de la base de datos
docker compose logs -f db

# Ver los últimos 100 registros con marcas de tiempo
docker compose logs --tail=100 -t app
```

### Reiniciar únicamente el microservicio
```bash
# Reiniciar el contenedor de la aplicación sin reiniciar la base de datos
docker compose restart app
```

### Detener el entorno conservando datos
```bash
# Detiene y elimina contenedores y redes, PERO conserva intacto el volumen con los datos de PostgreSQL
docker compose down
```

> [!CAUTION]
> **Eliminación Total de Datos (Destructivo):**
> Si ejecutas `docker compose down -v`, **se eliminará el volumen `corporate_billing_postgres_data` y todos los datos registrados en la base de datos se perderán de forma irreversible**. Usa esta bandera únicamente cuando requieras reiniciar la base de datos completamente desde cero.

---

## 8. Reconstruir tras Cambios en el Código

Cada vez que realices modificaciones en el código fuente de Java (`src/`):

```bash
# Recompilar el JAR y reconstruir únicamente la imagen de la aplicación en caliente
docker compose up -d --build app
```

---

## 9. Backup y Restauración de la Base de Datos

### Generar un respaldo (Backup)
```bash
# Exporta un archivo SQL con el esquema completo y todos los datos
docker compose exec -T db pg_dump -U billing_user -d facturacion > backup_facturacion_$(date +%Y%m%d_%H%M%S).sql
```

### Restaurar desde un respaldo
```bash
# Importa un archivo SQL de respaldo a la base de datos
cat backup_facturacion_ejemplo.sql | docker compose exec -T db psql -U billing_user -d facturacion
```

---

## 10. Migraciones de Base de Datos con Flyway

- **Al arrancar la aplicación**, Spring Boot ejecuta automáticamente las migraciones pendientes en `src/main/resources/db/migration/`.
- **Inmutabilidad de scripts:** Nunca modifiques un script ya aplicado (ej. `V1__esquema_inicial.sql`), ya que Flyway valida el checksum SHA-256 y fallará si detecta discrepancias.
- **Nuevas migraciones:** Agrega un nuevo archivo con prefijo correlativo (ej. `V2__descripcion.sql`).
- **Si una migración falla:**
  ```bash
  # Conectarse a la BD para inspeccionar la tabla de control de Flyway
  docker compose exec db psql -U billing_user -d facturacion -c "SELECT installed_rank, version, description, success FROM flyway_schema_history;"

  # Para limpiar migraciones fallidas después de corregir el script:
  # (Se puede ejecutar un comando flyway repair si se incluye el CLI o mediante consulta directa)
  ```

---

## 11. Despliegue en Servidor de Producción

En un entorno de producción (servidor Linux dedicado o VPS):

1. **No exponer el puerto 5432 al exterior:**
   Ejecuta Docker Compose indicando únicamente el archivo base (sin cargar `docker-compose.override.yml`):
   ```bash
   docker compose -f docker-compose.yml up -d
   ```
2. **Reinicios automáticos:**
   En producción se recomienda `restart: always`.
3. **Límites de Memoria y CPU:**
   Puedes agregar límites en un archivo `docker-compose.prod.yml`:
   ```yaml
   services:
     app:
       deploy:
         resources:
           limits:
             cpus: '2.0'
             memory: 2048M
   ```
4. **Rotación de Logs:**
   Evita que los archivos de logs saturen el disco configurando el driver `json-file`:
   ```yaml
   logging:
     driver: "json-file"
     options:
       max-size: "50m"
       max-file: "5"
   ```
5. **Reverse Proxy:**
   Coloca Nginx, Traefik o Caddy frente al puerto `8080` para gestionar certificados SSL/TLS (HTTPS) y balanceo de carga.

---

## 12. Conectividad con el Sistema de Órdenes

El microservicio consume endpoints REST del sistema de Órdenes para sincronizar clientes, sedes y novedades.

- **Si el sistema de Órdenes corre en tu misma máquina pero fuera de Docker:**
  En `docker-compose.yml` se ha configurado la directiva `extra_hosts: host.docker.internal:host-gateway`.
  Esto permite que la aplicación dentro del contenedor se comunique con tu máquina anfitriona mediante:
  ```env
  ORDENES_BASE_URL=http://host.docker.internal:8081
  ```
- **Si el sistema de Órdenes se encuentra en otro servidor o red:**
  Define directamente la URL remota en tu `.env`:
  ```env
  ORDENES_BASE_URL=https://ordenes.sistemaspalacios.com/api/v1
  ```

---

## 13. Solución de Problemas Frecuentes

### 1. "Docker Desktop is not running" o error al conectar con el socket de Docker
- **Síntoma:** Error `error during connect: This error may indicate that the docker daemon is not running`.
- **Causa:** Docker Desktop está cerrado o aún no ha terminado de inicializar su motor en segundo plano.
- **Solución:** Abre Docker Desktop desde el menú de inicio de Windows y espera hasta que el indicador de estado en la esquina inferior izquierda esté en **verde** (`Engine running`).

### 2. Conflicto con un PostgreSQL instalado directamente en Windows (Puerto 5432 ocupado)
- **Síntoma:** `Bind for 0.0.0.0:5432 failed: port is already allocated`.
- **Causa:** Tienes un servicio de PostgreSQL de Windows (ej. versión 14, 15 o 16) corriendo como servicio de Windows.
- **Solución:** Tienes dos alternativas:
  - **Opción A (Recomendada):** Cambia el puerto en tu `.env` a `POSTGRES_PORT=5433` y ejecuta `docker compose up -d`.
  - **Opción B:** Abre la aplicación de Servicios de Windows (`services.msc`), localiza `postgresql-x64-XX` y haz clic en "Detener".

### 3. El puerto 8080 ya está ocupado en el host
- **Síntoma:** `Bind for 0.0.0.0:8080 failed: port is already allocated`.
- **Causa:** Hay otra aplicación web (Tomcat, IIS, otro microservicio) escuchando en el puerto 8080.
- **Solución:** Cambia la variable en tu `.env` a `APP_PORT=8085` y vuelve a ejecutar `docker compose up -d`. Ahora accederás a `http://localhost:8085`.

### 4. La aplicación no conecta a la base de datos dentro de Docker
- **Síntoma:** Error `Connection refused` o `UnknownHostException: db` en los logs de Spring Boot.
- **Causa:** Se configuró erróneamente `localhost:5432` en lugar del nombre del servicio `db`. Dentro de la red de Docker, cada contenedor tiene su propia dirección IP y `localhost` se refiere a sí mismo.
- **Solución:** Asegurar que `SPRING_DATASOURCE_URL` utilice el host `db`:
  `jdbc:postgresql://db:5432/facturacion?stringtype=unspecified`.

### 5. Permisos en las carpetas de almacenamiento (Bind Mounts)
- **Síntoma:** `java.nio.file.AccessDeniedException` al intentar leer archivos de Siigo o escribir copias locales de PDF / archivos planos.
- **Causa:** El contenedor corre bajo el usuario no-root `spring` (UID 1000). La carpeta de origen `storage/siigo_compartida` se monta como solo lectura (`:ro`) y requiere permiso de lectura para el contenedor. La carpeta `storage/siigo-pdfs` se monta como lectura y escritura (`:rw`) para guardar las copias persistentes de los PDF recibidos y requiere permiso de escritura para `spring`.
- **Solución:** En Linux/macOS o WSL2, asegura la creación y ajusta los permisos de las carpetas locales:
  ```bash
  mkdir -p storage/siigo_compartida storage/siigo-pdfs storage/files
  chmod -R 775 storage/
  ```

### 6. Error de Checksum en Flyway
- **Síntoma:** `FlywayException: Validate failed: Migration checksum mismatch for migration version 1`.
- **Causa:** Se editó el archivo `V1__esquema_inicial.sql` después de que ya se había aplicado en la base de datos persistida en el volumen.
- **Solución:**
  - En desarrollo: Si puedes descartar datos de prueba, ejecuta `docker compose down -v` y vuelve a levantar con `docker compose up -d`.
  - En producción: Nunca modifiques un script ya aplicado; crea una nueva migración `V2__...sql`.

### 7. Falta de Memoria en el Contenedor (OOMKilled)
- **Síntoma:** El contenedor se apaga repentinamente con código de salida `137`.
- **Causa:** La JVM superó la memoria asignada por Docker Desktop.
- **Solución:**
  - En Docker Desktop ve a **Settings ⚙️ > Resources > Advanced** y sube la RAM a 4 GB o más.
  - El `Dockerfile` ya incluye `-XX:MaxRAMPercentage=75.0` para que la JVM asigne su heap dinámicamente hasta el 75% del límite del contenedor, dejando el 25% restante para memoria nativa y el sistema operativo.

