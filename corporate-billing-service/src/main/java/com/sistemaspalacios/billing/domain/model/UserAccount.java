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

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Usuario del panel (tabla usuario). La autenticación y la contraseña pertenecen al microservicio
 * OAuth2; este registro vincula la identidad del token (nombre de usuario o correo) con sus roles.
 */
@Entity
@Table(name = "usuario")
public class UserAccount {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._@-]{3,100}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    /** El último acceso se persiste como máximo cada 5 minutos para no escribir en cada petición. */
    private static final Duration LAST_LOGIN_GRANULARITY = Duration.ofMinutes(5);

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "nombre_usuario", nullable = false, length = 100)
    private String username;

    @Column(name = "correo", nullable = false, length = 255)
    private String email;

    @Column(name = "nombres", nullable = false, length = 100)
    private String firstName;

    @Column(name = "apellidos", nullable = false, length = 100)
    private String lastName;

    @Column(name = "activo", nullable = false)
    private boolean active = true;

    @Column(name = "bloqueado", nullable = false)
    private boolean locked;

    @Column(name = "eliminado", nullable = false)
    private boolean deleted;

    @Column(name = "ultimo_acceso")
    private Instant lastLoginAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @Column(name = "creado_por", length = 150, updatable = false)
    private String createdBy;

    @Column(name = "actualizado_por", length = 150)
    private String updatedBy;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "usuario_rol",
            joinColumns = @JoinColumn(name = "usuario_id"),
            inverseJoinColumns = @JoinColumn(name = "rol_id"))
    private Set<AccessRole> roles = new HashSet<>();

    protected UserAccount() {
        // JPA
    }

    public UserAccount(String username, String email, String firstName, String lastName, String actor) {
        this.id = UUID.randomUUID();
        this.username = normalizeUsername(username);
        this.createdAt = Instant.now();
        this.createdBy = actor;
        update(email, firstName, lastName, true, actor);
    }

    public static String normalizeUsername(String username) {
        String normalized = Objects.requireNonNull(username, "El usuario es obligatorio").trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("El usuario debe tener entre 3 y 100 caracteres: letras, números, punto, guion o @");
        }
        return normalized;
    }

    public static String normalizeEmail(String email) {
        String normalized = Objects.requireNonNull(email, "El correo es obligatorio").trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !EMAIL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("El correo no es válido");
        }
        return normalized;
    }

    public void update(String email, String firstName, String lastName, boolean active, String actor) {
        this.email = normalizeEmail(email);
        this.firstName = required(firstName, "El nombre es obligatorio");
        this.lastName = required(lastName, "El apellido es obligatorio");
        this.active = active;
        touch(actor);
    }

    public void changeStatus(boolean active, String actor) {
        this.active = active;
        touch(actor);
    }

    public void replaceRoles(Collection<AccessRole> newRoles, String actor) {
        roles.clear();
        roles.addAll(newRoles);
        touch(actor);
    }

    /** Eliminación lógica: se conserva el registro para la auditoría y se retira todo acceso. */
    public void markDeleted(String actor) {
        this.deleted = true;
        this.active = false;
        roles.clear();
        touch(actor);
    }

    /** Puede autenticarse en el panel: activo, no bloqueado y no eliminado. */
    public boolean canSignIn() {
        return active && !locked && !deleted;
    }

    /** Registra el acceso si el último guardado es más antiguo que la granularidad. */
    public boolean registerAccess(Instant now) {
        if (lastLoginAt != null && Duration.between(lastLoginAt, now).compareTo(LAST_LOGIN_GRANULARITY) < 0) {
            return false;
        }
        this.lastLoginAt = now;
        return true;
    }

    public String fullName() {
        return (firstName + " " + lastName).trim();
    }

    private void touch(String actor) {
        this.updatedAt = Instant.now();
        this.updatedBy = actor;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        String trimmed = value.trim();
        if (trimmed.length() > 100) throw new IllegalArgumentException(message.replace("es obligatorio", "admite máximo 100 caracteres"));
        return trimmed;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public boolean isActive() { return active; }
    public boolean isLocked() { return locked; }
    public boolean isDeleted() { return deleted; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public Set<AccessRole> getRoles() { return roles; }
}
