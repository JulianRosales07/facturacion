package com.sistemaspalacios.billing.domain.event;

import java.util.UUID;

/**
 * Cambió la configuración de acceso. Solo lleva identificadores (nunca datos sensibles): quien lo
 * recibe vuelve a consultar sus permisos por REST.
 */
public record AccessControlChanged(String eventType, UUID userId, UUID roleId, UUID permissionId) {

    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_ROLES_CHANGED = "USER_ROLES_CHANGED";
    public static final String ROLE_UPDATED = "ROLE_UPDATED";
    public static final String ROLE_PERMISSIONS_CHANGED = "ROLE_PERMISSIONS_CHANGED";
    public static final String PERMISSION_UPDATED = "PERMISSION_UPDATED";

    public static AccessControlChanged user(String type, UUID userId) {
        return new AccessControlChanged(type, userId, null, null);
    }

    public static AccessControlChanged role(String type, UUID roleId) {
        return new AccessControlChanged(type, null, roleId, null);
    }

    public static AccessControlChanged permission(UUID permissionId) {
        return new AccessControlChanged(PERMISSION_UPDATED, null, null, permissionId);
    }
}
