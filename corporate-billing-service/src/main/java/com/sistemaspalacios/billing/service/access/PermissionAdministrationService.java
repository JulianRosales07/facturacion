package com.sistemaspalacios.billing.service.access;

import com.sistemaspalacios.billing.domain.event.AccessControlChanged;
import com.sistemaspalacios.billing.domain.model.AccessPermission;
import com.sistemaspalacios.billing.domain.model.AccessRole;
import com.sistemaspalacios.billing.repository.RepositorioPermiso;
import com.sistemaspalacios.billing.repository.RepositorioRol;
import com.sistemaspalacios.billing.service.access.AccessDtos.PermissionRequest;
import com.sistemaspalacios.billing.service.access.AccessDtos.PermissionResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Catálogo de permisos. Los permisos del sistema (los que exige el código) conservan su código y
 * no se eliminan; sí pueden renombrarse, describirse o desactivarse.
 */
@Service
public class PermissionAdministrationService {

    private final RepositorioPermiso permissions;
    private final RepositorioRol roles;
    private final AccessAuditService audit;
    private final AdministratorSafeguard safeguard;
    private final ApplicationEventPublisher events;

    public PermissionAdministrationService(RepositorioPermiso permissions, RepositorioRol roles, AccessAuditService audit,
                                           AdministratorSafeguard safeguard, ApplicationEventPublisher events) {
        this.permissions = permissions;
        this.roles = roles;
        this.audit = audit;
        this.safeguard = safeguard;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> list() {
        Map<UUID, Long> roleCounts = permissions.countRolesPerPermission().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
        return permissions.findAllByOrderByModuleAscCodeAsc().stream()
                .map(p -> AccessMapper.permission(p, roleCounts.getOrDefault(p.getId(), 0L), null))
                .toList();
    }

    @Transactional(readOnly = true)
    public PermissionResponse get(UUID id) {
        AccessPermission permission = find(id);
        List<AccessRole> assigned = roles.findByPermissionId(id);
        return AccessMapper.permission(permission, assigned.size(), assigned);
    }

    @Transactional
    public PermissionResponse create(PermissionRequest request) {
        String code = AccessPermission.normalizeCode(request.code());
        if (permissions.existsCode(code, null)) {
            throw new AccessConflictException("El permiso '" + code + "' ya existe");
        }
        AccessPermission permission = new AccessPermission(code, request.name(), request.description(), request.module());
        if (Boolean.FALSE.equals(request.active())) {
            permission.update(request.name(), request.description(), request.module(), false);
        }
        permissions.save(permission);
        audit.record("PERMISO_CREADO", AccessAuditService.PERMISSION, permission.getId(), "codigo=" + code);
        return AccessMapper.permission(permission, 0, List.of());
    }

    @Transactional
    public PermissionResponse update(UUID id, PermissionRequest request) {
        return safeguard.guard(() -> {
            AccessPermission permission = find(id);
            UserAdministrationService.checkVersion(permission.getVersion(), request.version());
            String code = AccessPermission.normalizeCode(request.code());
            if (permissions.existsCode(code, id)) {
                throw new AccessConflictException("El permiso '" + code + "' ya existe");
            }
            permission.changeCode(code);
            boolean active = request.active() == null ? permission.isActive() : request.active();
            permission.update(request.name(), request.description(), request.module(), active);
            audit.record("PERMISO_EDITADO", AccessAuditService.PERMISSION, id, "codigo=" + code + " activo=" + active);
            events.publishEvent(AccessControlChanged.permission(id));
            List<AccessRole> assigned = roles.findByPermissionId(id);
            return AccessMapper.permission(permission, assigned.size(), assigned);
        });
    }

    @Transactional
    public void delete(UUID id) {
        safeguard.guard(() -> {
            AccessPermission permission = find(id);
            if (permission.isSystemPermission()) {
                throw new AccessConflictException("El permiso '" + permission.getCode()
                        + "' es del sistema y no se puede eliminar; puedes desactivarlo");
            }
            for (AccessRole role : roles.findByPermissionId(id)) {
                var remaining = new LinkedHashSet<>(role.getPermissions());
                remaining.remove(permission);
                role.replacePermissions(remaining);
                events.publishEvent(AccessControlChanged.role(AccessControlChanged.ROLE_PERMISSIONS_CHANGED, role.getId()));
            }
            permissions.delete(permission);
            audit.record("PERMISO_ELIMINADO", AccessAuditService.PERMISSION, id, "codigo=" + permission.getCode());
            events.publishEvent(AccessControlChanged.permission(id));
            return null;
        });
    }

    private AccessPermission find(UUID id) {
        return permissions.findById(id).orElseThrow(() -> new NoSuchElementException("Permiso no encontrado: " + id));
    }
}
