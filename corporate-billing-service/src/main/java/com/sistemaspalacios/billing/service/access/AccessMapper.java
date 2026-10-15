package com.sistemaspalacios.billing.service.access;

import com.sistemaspalacios.billing.domain.model.AccessPermission;
import com.sistemaspalacios.billing.domain.model.AccessRole;
import com.sistemaspalacios.billing.domain.model.UserAccount;
import com.sistemaspalacios.billing.service.access.AccessDtos.PermissionRef;
import com.sistemaspalacios.billing.service.access.AccessDtos.PermissionResponse;
import com.sistemaspalacios.billing.service.access.AccessDtos.RoleRef;
import com.sistemaspalacios.billing.service.access.AccessDtos.RoleResponse;
import com.sistemaspalacios.billing.service.access.AccessDtos.UserRef;
import com.sistemaspalacios.billing.service.access.AccessDtos.UserResponse;

import java.util.Comparator;
import java.util.List;

/** Entidades → DTO. Se invoca dentro de la transacción para leer las relaciones perezosas. */
final class AccessMapper {

    private AccessMapper() {}

    static RoleRef roleRef(AccessRole role) {
        return new RoleRef(role.getId(), role.getName(), role.isActive());
    }

    static PermissionRef permissionRef(AccessPermission permission) {
        return new PermissionRef(permission.getId(), permission.getCode(), permission.getName(), permission.getModule());
    }

    static UserRef userRef(UserAccount user) {
        return new UserRef(user.getId(), user.getUsername(), user.fullName(), user.isActive());
    }

    static UserResponse user(UserAccount user, List<String> effectivePermissions) {
        List<RoleRef> roles = user.getRoles().stream()
                .sorted(Comparator.comparing(AccessRole::getName)).map(AccessMapper::roleRef).toList();
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.fullName(), user.isActive(), user.isLocked(), user.getLastLoginAt(), roles, effectivePermissions,
                user.getVersion(), user.getCreatedAt(), user.getUpdatedAt(), user.getCreatedBy(), user.getUpdatedBy());
    }

    static RoleResponse role(AccessRole role, long userCount, List<UserAccount> users, boolean detail) {
        List<PermissionRef> permissions = detail ? role.getPermissions().stream()
                .sorted(Comparator.comparing(AccessPermission::getModule).thenComparing(AccessPermission::getCode))
                .map(AccessMapper::permissionRef).toList() : null;
        List<UserRef> userRefs = users == null ? null : users.stream().map(AccessMapper::userRef).toList();
        return new RoleResponse(role.getId(), role.getName(), role.getDescription(), role.isActive(), role.isSystemRole(),
                userCount, role.getPermissions().size(), permissions, userRefs,
                role.getVersion(), role.getCreatedAt(), role.getUpdatedAt());
    }

    static PermissionResponse permission(AccessPermission permission, long roleCount, List<AccessRole> roles) {
        List<RoleRef> roleRefs = roles == null ? null : roles.stream().map(AccessMapper::roleRef).toList();
        return new PermissionResponse(permission.getId(), permission.getCode(), permission.getName(),
                permission.getDescription(), permission.getModule(), permission.isActive(), permission.isSystemPermission(),
                roleCount, roleRefs, permission.getVersion(), permission.getCreatedAt(), permission.getUpdatedAt());
    }
}
