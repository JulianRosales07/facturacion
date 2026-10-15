package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Permiso del catálogo (tabla permiso). Su {@code code} es la autoridad que exigen los
 * {@code @PreAuthorize}; los permisos del sistema conservan siempre su código.
 */
@Entity
@Table(name = "permiso")
public class AccessPermission {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,79}$");

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "codigo", nullable = false, unique = true, length = 80)
    private String code;

    @Column(name = "nombre", nullable = false, length = 120)
    private String name;

    @Column(name = "descripcion", length = 255)
    private String description;

    @Column(name = "modulo", nullable = false, length = 60)
    private String module;

    @Column(name = "activo", nullable = false)
    private boolean active = true;

    @Column(name = "sistema", nullable = false, updatable = false)
    private boolean systemPermission;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    protected AccessPermission() {
        // JPA
    }

    public AccessPermission(String code, String name, String description, String module) {
        this.id = UUID.randomUUID();
        this.code = normalizeCode(code);
        this.createdAt = Instant.now();
        update(name, description, module, true);
    }

    /** Códigos en MAYÚSCULAS_CON_GUION_BAJO, como los que usa el backend. */
    public static String normalizeCode(String code) {
        String normalized = Objects.requireNonNull(code, "El código del permiso es obligatorio").trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("El código del permiso debe usar mayúsculas, números y guion bajo (ej. INVOICES_VIEW)");
        }
        return normalized;
    }

    public void update(String name, String description, String module, boolean active) {
        this.name = required(name, "El nombre del permiso es obligatorio");
        this.module = required(module, "El módulo del permiso es obligatorio");
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.active = active;
        this.updatedAt = Instant.now();
    }

    /** Solo los permisos creados desde la administración pueden cambiar de código. */
    public void changeCode(String newCode) {
        String normalized = normalizeCode(newCode);
        if (systemPermission && !normalized.equals(code)) {
            throw new IllegalStateException("El código de un permiso del sistema no se puede modificar");
        }
        this.code = normalized;
        this.updatedAt = Instant.now();
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getModule() { return module; }
    public boolean isActive() { return active; }
    public boolean isSystemPermission() { return systemPermission; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
