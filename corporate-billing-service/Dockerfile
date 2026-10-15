# ==============================================================================
# Etapa 1: Build (Compilación y Empaquetado)
# ==============================================================================
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copiar archivos de configuración de Gradle primero para optimizar la caché de capas
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Asegurar permisos de ejecución y normalizar saltos de línea (CRLF a LF)
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# Descarga y resolución previa de dependencias
RUN ./gradlew dependencies --no-daemon

# Copiar código fuente
COPY src src

# Compilar y empaquetar artefacto ejecutable omitiendo pruebas
RUN ./gradlew clean bootJar -x test --no-daemon

# ==============================================================================
# Etapa 2: Runtime (Imagen de Producción Ligera y Segura)
# ==============================================================================
FROM eclipse-temurin:21-jre-jammy AS runtime

# Instalar curl para healthcheck y chromium para generación headless de PDFs oficiales
RUN apt-get update && apt-get install -y --no-install-recommends curl chromium-browser fonts-liberation && rm -rf /var/lib/apt/lists/* || \
    (apt-get update && apt-get install -y --no-install-recommends curl chromium fonts-liberation && rm -rf /var/lib/apt/lists/*)

# Crear usuario y grupo no-root para seguridad
RUN groupadd -r spring && useradd -r -g spring -u 1000 -m -s /bin/bash spring

WORKDIR /app

# Crear directorios de almacenamiento para monturas compartidas
RUN mkdir -p /app/storage/siigo_compartida /app/storage/siigo-pdfs /app/storage/files && \
    chown -R spring:spring /app

# Copiar el artefacto generado desde la etapa build
COPY --from=build --chown=spring:spring /workspace/build/libs/*.jar /app/app.jar

# Cambiar a usuario no-root
USER spring:spring

# Puerto expuesto por el microservicio
EXPOSE 8080

# Opciones por defecto de la JVM respetando límites de RAM en contenedor
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Verificación de salud periódica mediante Spring Boot Actuator
HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Ejecución de la aplicación
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
