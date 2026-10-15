package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Rol (tabla rol): agrupa permisos. Los roles del sistema no se renombran ni se eliminan. */
@Entity
@Table(name = "rol")
public class AccessRole {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final Pattern NAME = Pattern.compile("^[A-Z][A-Z0-9_]{2,59}$");

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "nombre", nullable = false, unique = true, length = 60)
    private String name;

    @Column(name = "descripcion", length = 255)
    private String description;

    @Column(name = "activo", nullable = false)
    private boolean active = true;

    @Column(name = "sistema", nullable = false, updatable = false)
    private boolean systemRole;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "rol_permiso",
            joinColumns = @JoinColumn(name = "rol_id"),
            inverseJoinColumns = @JoinColumn(name = "permiso_id"))
    private Set<AccessPermission> permissions = new HashSet<>();

    protected AccessRole() {
        // JPA
    }

    public AccessRole(String name, String description) {
        this.id = UUID.randomUUID();
        this.name = normalizeName(name);
        this.createdAt = Instant.now();
        update(this.name, description, true);
    }

    /** Nombres en MAYÚSCULAS_CON_GUION_BAJO (ej. ANALISTA_CARTERA). */
    public static String normalizeName(String name) {
        String normalized = Objects.requireNonNull(name, "El nombre del rol es obligatorio").trim()
                .toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (!NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("El nombre del rol debe usar mayúsculas, números y guion bajo (ej. ANALISTA_CARTERA)");
        }
        return normalized;
    }

    public void update(String newName, String description, boolean active) {
        String normalized = normalizeName(newName);
        if (systemRole && !normalized.equals(name)) {
            throw new IllegalStateException("Un rol del sistema no se puede renombrar");
        }
        this.name = normalized;
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.active = active;
        this.updatedAt = Instant.now();
    }

    /** Reemplaza todos los permisos del rol. */
    public void replacePermissions(Collection<AccessPermission> newPermissions) {
        permissions.clear();
        permissions.addAll(newPermissions);
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public boolean isSystemRole() { return systemRole; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Set<AccessPermission> getPermissions() { return permissions; }
}
