package com.sistemaspalacios.billing.service.access;

import com.sistemaspalacios.billing.domain.event.AccessControlChanged;
import com.sistemaspalacios.billing.domain.model.AccessPermission;
import com.sistemaspalacios.billing.domain.model.AccessRole;
import com.sistemaspalacios.billing.domain.model.UserAccount;
import com.sistemaspalacios.billing.repository.RepositorioPermiso;
import com.sistemaspalacios.billing.repository.RepositorioRol;
import com.sistemaspalacios.billing.repository.RepositorioUsuario;
import com.sistemaspalacios.billing.service.access.AccessDtos.RoleRequest;
import com.sistemaspalacios.billing.service.access.AccessDtos.RoleResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Administración de roles y de su asignación de permisos. */
@Service
public class RoleAdministrationService {

    private final RepositorioRol roles;
    private final RepositorioPermiso permissions;
    private final RepositorioUsuario users;
    private final AccessAuditService audit;
    private final AdministratorSafeguard safeguard;
    private final ApplicationEventPublisher events;

    public RoleAdministrationService(RepositorioRol roles, RepositorioPermiso permissions, RepositorioUsuario users,
                                     AccessAuditService audit, AdministratorSafeguard safeguard, ApplicationEventPublisher events) {
        this.roles = roles;
        this.permissions = permissions;
        this.users = users;
        this.audit = audit;
        this.safeguard = safeguard;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        Map<UUID, Long> userCounts = users.countUsersPerRole().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
        return roles.findAllByOrderByNameAsc().stream()
                .map(role -> AccessMapper.role(role, userCounts.getOrDefault(role.getId(), 0L), null, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleResponse get(UUID id) {
        AccessRole role = find(id);
        List<UserAccount> assigned = users.findByRoleId(id);
        return AccessMapper.role(role, assigned.size(), assigned, true);
    }

    @Transactional
    public RoleResponse create(RoleRequest request) {
        String name = AccessRole.normalizeName(request.name());
        if (roles.existsName(name, null)) {
            throw new AccessConflictException("El rol '" + name + "' ya existe");
        }
        AccessRole role = new AccessRole(name, request.description());
        if (Boolean.FALSE.equals(request.active())) {
            role.update(name, request.description(), false);
        }
        role.replacePermissions(resolvePermissions(request.permissionIds()));
        roles.save(role);
        audit.record("ROL_CREADO", AccessAuditService.ROLE, role.getId(), "rol=" + name + " permisos=" + codes(role));
        return AccessMapper.role(role, 0, List.of(), true);
    }

    @Transactional
    public RoleResponse update(UUID id, RoleRequest request) {
        return safeguard.guard(() -> {
            AccessRole role = find(id);
            UserAdministrationService.checkVersion(role.getVersion(), request.version());
            String name = AccessRole.normalizeName(request.name());
            if (roles.existsName(name, id)) {
                throw new AccessConflictException("El rol '" + name + "' ya existe");
            }
            boolean active = request.active() == null ? role.isActive() : request.active();
            role.update(name, request.description(), active);
            audit.record("ROL_EDITADO", AccessAuditService.ROLE, id, "rol=" + name + " activo=" + active);
            events.publishEvent(AccessControlChanged.role(AccessControlChanged.ROLE_UPDATED, id));
            if (request.permissionIds() != null) {
                applyPermissions(role, request.permissionIds());
            }
            return AccessMapper.role(role, users.findByRoleId(id).size(), null, true);
        });
    }

    /** Reemplaza de forma transaccional todos los permisos del rol. */
    @Transactional
    public RoleResponse replacePermissions(UUID id, List<UUID> permissionIds) {
        return safeguard.guard(() -> {
            AccessRole role = find(id);
            applyPermissions(role, permissionIds);
            return AccessMapper.role(role, users.findByRoleId(id).size(), null, true);
        });
    }

    /** Los roles del sistema no se eliminan; al eliminar uno propio se retira a sus usuarios. */
    @Transactional
    public void delete(UUID id) {
        safeguard.guard(() -> {
            AccessRole role = find(id);
            if (role.isSystemRole()) {
                throw new AccessConflictException("El rol '" + role.getName() + "' es del sistema y no se puede eliminar");
            }
            List<UserAccount> assigned = users.findByRoleId(id);
            for (UserAccount user : assigned) {
                Set<AccessRole> remaining = new LinkedHashSet<>(user.getRoles());
                remaining.remove(role);
                user.replaceRoles(remaining, audit.actor());
                events.publishEvent(AccessControlChanged.user(AccessControlChanged.USER_ROLES_CHANGED, user.getId()));
            }
            roles.delete(role);
            audit.record("ROL_ELIMINADO", AccessAuditService.ROLE, id,
                    "rol=" + role.getName() + " usuariosAfectados=" + assigned.size());
            events.publishEvent(AccessControlChanged.role(AccessControlChanged.ROLE_UPDATED, id));
            return null;
        });
    }

    private void applyPermissions(AccessRole role, List<UUID> permissionIds) {
        String before = codes(role);
        role.replacePermissions(resolvePermissions(permissionIds));
        String after = codes(role);
        if (!before.equals(after)) {
            audit.record("PERMISOS_MODIFICADOS", AccessAuditService.ROLE, role.getId(), "antes=" + before + " después=" + after);
            events.publishEvent(AccessControlChanged.role(AccessControlChanged.ROLE_PERMISSIONS_CHANGED, role.getId()));
        }
    }

    private Set<AccessPermission> resolvePermissions(List<UUID> permissionIds) {
        Set<UUID> ids = new LinkedHashSet<>(permissionIds == null ? List.of() : permissionIds);
        ids.remove(null);
        List<AccessPermission> found = permissions.findAllById(ids);
        if (found.size() != ids.size()) {
            Set<UUID> existing = found.stream().map(AccessPermission::getId).collect(Collectors.toSet());
            throw new NoSuchElementException("Permisos inexistentes: " + ids.stream().filter(i -> !existing.contains(i)).toList());
        }
        return new LinkedHashSet<>(found);
    }

    private AccessRole find(UUID id) {
        return roles.findById(id).orElseThrow(() -> new NoSuchElementException("Rol no encontrado: " + id));
    }

    private static String codes(AccessRole role) {
        return role.getPermissions().stream().map(AccessPermission::getCode).sorted().toList().toString();
    }
}
