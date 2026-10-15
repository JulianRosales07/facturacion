package com.sistemaspalacios.billing.service.access;

import com.sistemaspalacios.billing.domain.model.AccessRole;
import com.sistemaspalacios.billing.domain.model.UserAccount;
import com.sistemaspalacios.billing.repository.RepositorioPermiso;
import com.sistemaspalacios.billing.repository.RepositorioUsuario;
import com.sistemaspalacios.billing.service.access.AccessDtos.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Permisos efectivos del usuario autenticado. Se consultan en cada petición: un cambio de roles o
 * permisos aplica de inmediato, sin esperar a que el token expire ni volver a iniciar sesión.
 */
@Service
public class CurrentAccessService {

    /** Usuario sintético del modo de desarrollo (auth.enabled=false). */
    public static final String LOCAL_USER = "admin-local";

    private final RepositorioUsuario users;
    private final RepositorioPermiso permissions;

    public CurrentAccessService(RepositorioUsuario users, RepositorioPermiso permissions) {
        this.users = users;
        this.permissions = permissions;
    }

    /**
     * Vincula la identidad del token con un usuario registrado: primero por nombre de usuario y,
     * si no existe, por correo. Sin registro, o inactivo, bloqueado o eliminado: sin permisos.
     */
    @Transactional
    public Optional<CurrentUser> resolve(String login, String email) {
        Optional<UserAccount> user = Optional.empty();
        if (login != null && !login.isBlank()) {
            user = users.findByUsername(login.trim());
            if (user.isEmpty() && login.contains("@")) user = users.findByEmail(login.trim());
        }
        if (user.isEmpty() && email != null && !email.isBlank()) {
            user = users.findByEmail(email.trim());
        }
        return user.filter(UserAccount::canSignIn).map(account -> {
            account.registerAccess(Instant.now());
            return new CurrentUser(account.getId(), account.getUsername(), account.fullName(), account.getEmail(), true,
                    users.findActiveRoleNames(account.getId()), users.findEffectivePermissionCodes(account.getId()));
        });
    }

    /** Identidad autenticada pero sin acceso al panel (no registrada o deshabilitada). */
    public CurrentUser unregistered(String login, String name, String email) {
        return new CurrentUser(null, login, name, email, false, List.of(), List.of());
    }

    /** Modo de desarrollo local: un administrador con todos los permisos activos del catálogo. */
    @Transactional(readOnly = true)
    public CurrentUser localDevelopmentUser() {
        return new CurrentUser(null, LOCAL_USER, "Administrador local", null, true,
                List.of(AccessRole.SUPER_ADMIN), permissions.findActiveCodes());
    }
}
