package com.sistemaspalacios.billing.service.access;

import com.sistemaspalacios.billing.domain.event.AccessControlChanged;
import com.sistemaspalacios.billing.domain.model.AccessRole;
import com.sistemaspalacios.billing.domain.model.UserAccount;
import com.sistemaspalacios.billing.repository.RepositorioRol;
import com.sistemaspalacios.billing.repository.RepositorioUsuario;
import com.sistemaspalacios.billing.service.access.AccessDtos.PageResponse;
import com.sistemaspalacios.billing.service.access.AccessDtos.StatusRequest;
import com.sistemaspalacios.billing.service.access.AccessDtos.UserFilter;
import com.sistemaspalacios.billing.service.access.AccessDtos.UserRequest;
import com.sistemaspalacios.billing.service.access.AccessDtos.UserResponse;
import com.sistemaspalacios.billing.service.access.AccessDtos.UserUpdateRequest;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Administración de usuarios: alta, edición, estado, roles y eliminación lógica. */
@Service
public class UserAdministrationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final RepositorioUsuario users;
    private final RepositorioRol roles;
    private final AccessAuditService audit;
    private final AdministratorSafeguard safeguard;
    private final ApplicationEventPublisher events;

    public UserAdministrationService(RepositorioUsuario users, RepositorioRol roles, AccessAuditService audit,
                                     AdministratorSafeguard safeguard, ApplicationEventPublisher events) {
        this.users = users;
        this.roles = roles;
        this.audit = audit;
        this.safeguard = safeguard;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(UserFilter filter, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<UserAccount> result = users.findAll(specification(filter),
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by("username")));
        List<UserResponse> items = result.getContent().stream().map(u -> AccessMapper.user(u, null)).toList();
        return new PageResponse<>(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        UserAccount user = find(id);
        return AccessMapper.user(user, users.findEffectivePermissionCodes(id));
    }

    @Transactional
    public UserResponse create(UserRequest request) {
        if (request.password() != null && !request.password().isBlank()) {
            throw new IllegalArgumentException("Las contraseñas se gestionan en el microservicio de autenticación; "
                    + "este servicio no recibe ni almacena contraseñas.");
        }
        String username = UserAccount.normalizeUsername(request.username());
        String email = UserAccount.normalizeEmail(request.email());
        if (users.existsUsername(username)) {
            throw new AccessConflictException("El usuario '" + username + "' ya existe");
        }
        if (users.existsEmail(email, null)) {
            throw new AccessConflictException("El correo '" + email + "' ya está registrado");
        }
        UserAccount user = new UserAccount(username, email, request.firstName(), request.lastName(), audit.actor());
        if (Boolean.FALSE.equals(request.active())) {
            user.changeStatus(false, audit.actor());
        }
        user.replaceRoles(resolveRoles(request.roleIds()), audit.actor());
        users.save(user);
        audit.record("USUARIO_CREADO", AccessAuditService.USER, user.getId(),
                "usuario=" + username + " roles=" + roleNames(user));
        events.publishEvent(AccessControlChanged.user(AccessControlChanged.USER_UPDATED, user.getId()));
        return AccessMapper.user(user, null);
    }

    @Transactional
    public UserResponse update(UUID id, UserUpdateRequest request) {
        return safeguard.guard(() -> {
            UserAccount user = find(id);
            checkVersion(user.getVersion(), request.version());
            String email = UserAccount.normalizeEmail(request.email());
            if (users.existsEmail(email, id)) {
                throw new AccessConflictException("El correo '" + email + "' ya está registrado");
            }
            boolean active = request.active() == null ? user.isActive() : request.active();
            user.update(email, request.firstName(), request.lastName(), active, audit.actor());
            audit.record("USUARIO_EDITADO", AccessAuditService.USER, id, "correo=" + email + " activo=" + active);
            if (request.roleIds() != null) {
                applyRoles(user, request.roleIds());
            }
            events.publishEvent(AccessControlChanged.user(AccessControlChanged.USER_UPDATED, id));
            return AccessMapper.user(user, null);
        });
    }

    @Transactional
    public UserResponse changeStatus(UUID id, StatusRequest request) {
        return safeguard.guard(() -> {
            UserAccount user = find(id);
            checkVersion(user.getVersion(), request.version());
            user.changeStatus(request.active(), audit.actor());
            audit.record(request.active() ? "USUARIO_ACTIVADO" : "USUARIO_DESACTIVADO", AccessAuditService.USER, id, null);
            events.publishEvent(AccessControlChanged.user(AccessControlChanged.USER_UPDATED, id));
            return AccessMapper.user(user, null);
        });
    }

    @Transactional
    public UserResponse replaceRoles(UUID id, List<UUID> roleIds) {
        return safeguard.guard(() -> {
            UserAccount user = find(id);
            applyRoles(user, roleIds);
            return AccessMapper.user(user, null);
        });
    }

    /** Eliminación lógica: el registro queda para la auditoría, sin roles y sin acceso. */
    @Transactional
    public void delete(UUID id) {
        safeguard.guard(() -> {
            UserAccount user = find(id);
            if (user.getUsername().equalsIgnoreCase(audit.actor())) {
                throw new AccessConflictException("No puedes eliminar tu propio usuario");
            }
            user.markDeleted(audit.actor());
            audit.record("USUARIO_ELIMINADO", AccessAuditService.USER, id, "usuario=" + user.getUsername());
            events.publishEvent(AccessControlChanged.user(AccessControlChanged.USER_UPDATED, id));
            return null;
        });
    }

    private void applyRoles(UserAccount user, List<UUID> roleIds) {
        String before = roleNames(user);
        user.replaceRoles(resolveRoles(roleIds), audit.actor());
        String after = roleNames(user);
        if (!before.equals(after)) {
            audit.record("ROLES_ASIGNADOS", AccessAuditService.USER, user.getId(), "antes=" + before + " después=" + after);
            events.publishEvent(AccessControlChanged.user(AccessControlChanged.USER_ROLES_CHANGED, user.getId()));
        }
    }

    private Set<AccessRole> resolveRoles(List<UUID> roleIds) {
        Set<UUID> ids = new LinkedHashSet<>(roleIds == null ? List.of() : roleIds);
        ids.remove(null);
        List<AccessRole> found = roles.findAllById(ids);
        if (found.size() != ids.size()) {
            Set<UUID> existing = found.stream().map(AccessRole::getId).collect(Collectors.toSet());
            List<UUID> missing = ids.stream().filter(i -> !existing.contains(i)).toList();
            throw new NoSuchElementException("Roles inexistentes: " + missing);
        }
        return new LinkedHashSet<>(found);
    }

    private UserAccount find(UUID id) {
        return users.findActiveRecord(id).orElseThrow(() -> new NoSuchElementException("Usuario no encontrado: " + id));
    }

    static void checkVersion(Long current, Long expected) {
        if (expected != null && !Objects.equals(current, expected)) {
            throw new AccessConflictException("El registro fue modificado por otro administrador. Recarga la información e intenta de nuevo.");
        }
    }

    private static String roleNames(UserAccount user) {
        return user.getRoles().stream().map(AccessRole::getName).sorted().toList().toString();
    }

    private static Specification<UserAccount> specification(UserFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (has(filter.username())) {
                predicates.add(cb.like(cb.lower(root.get("username")), like(filter.username())));
            }
            if (has(filter.email())) {
                predicates.add(cb.like(cb.lower(root.get("email")), like(filter.email())));
            }
            if (has(filter.name())) {
                var fullName = cb.lower(cb.concat(cb.concat(root.get("firstName"), " "), root.get("lastName")));
                predicates.add(cb.like(fullName, like(filter.name())));
            }
            if (filter.active() != null) {
                predicates.add(cb.equal(root.get("active"), filter.active()));
            }
            if (has(filter.role())) {
                // Subconsulta: filtrar por rol sin duplicar filas ni afectar la paginación.
                Subquery<UUID> withRole = query.subquery(UUID.class);
                var user = withRole.from(UserAccount.class);
                var role = user.join("roles", JoinType.INNER);
                String value = filter.role().trim();
                Predicate byRole = cb.equal(role.get("name"), value.toUpperCase(Locale.ROOT));
                try {
                    byRole = cb.or(byRole, cb.equal(role.get("id"), UUID.fromString(value)));
                } catch (IllegalArgumentException notAnId) {
                    // Se filtra solo por nombre.
                }
                withRole.select(user.get("id")).where(byRole);
                predicates.add(root.get("id").in(withRole));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static String like(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "\\_") + "%";
    }
}
