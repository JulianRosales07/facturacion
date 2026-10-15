package com.sistemaspalacios.billing.service.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contratos de la administración de accesos. Nunca exponen entidades JPA: así no hay relaciones
 * perezosas, recursión ni datos internos en el JSON.
 */
public final class AccessDtos {

    private AccessDtos() {}

    // ---------- Peticiones ----------

    /**
     * Alta de usuario. {@code password} existe solo para rechazarlo con un mensaje claro: las
     * contraseñas pertenecen al microservicio de autenticación y aquí no se almacenan.
     */
    public record UserRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 255) String email,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            Boolean active,
            List<UUID> roleIds,
            String password
    ) {}

    /** Edición de usuario; {@code roleIds} nulo conserva los roles actuales. */
    public record UserUpdateRequest(
            @NotBlank @Size(max = 255) String email,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            Boolean active,
            List<UUID> roleIds,
            Long version
    ) {}

    public record StatusRequest(@NotNull Boolean active, Long version) {}

    public record RoleAssignmentRequest(@NotNull List<UUID> roleIds) {}

    /** Alta o edición de rol; en la edición {@code permissionIds} nulo conserva los permisos. */
    public record RoleRequest(
            @NotBlank @Size(max = 60) String name,
            @Size(max = 255) String description,
            Boolean active,
            List<UUID> permissionIds,
            Long version
    ) {}

    public record PermissionAssignmentRequest(@NotNull List<UUID> permissionIds) {}

    public record PermissionRequest(
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 255) String description,
            @NotBlank @Size(max = 60) String module,
            Boolean active,
            Long version
    ) {}

    public record UserFilter(String username, String name, String email, Boolean active, String role) {}

    // ---------- Respuestas ----------

    public record RoleRef(UUID id, String name, boolean active) {}

    public record PermissionRef(UUID id, String code, String name, String module) {}

    public record UserRef(UUID id, String username, String fullName, boolean active) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserResponse(
            UUID id, String username, String email, String firstName, String lastName, String fullName,
            boolean active, boolean locked, Instant lastLoginAt, List<RoleRef> roles,
            /** Permisos efectivos: solo en el detalle. */
            List<String> permissions,
            Long version, Instant createdAt, Instant updatedAt, String createdBy, String updatedBy
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoleResponse(
            UUID id, String name, String description, boolean active, boolean systemRole,
            long userCount, long permissionCount,
            /** Solo en el detalle. */
            List<PermissionRef> permissions,
            List<UserRef> users,
            Long version, Instant createdAt, Instant updatedAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PermissionResponse(
            UUID id, String code, String name, String description, String module, boolean active,
            boolean systemPermission, long roleCount,
            /** Solo en el detalle. */
            List<RoleRef> roles,
            Long version, Instant createdAt, Instant updatedAt
    ) {}

    public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}

    /** Usuario autenticado y sus permisos efectivos (GET /api/v1/auth/me). */
    public record CurrentUser(
            UUID id, String username, String name, String email, boolean registered,
            List<String> roles, List<String> permissions
    ) {}
}
