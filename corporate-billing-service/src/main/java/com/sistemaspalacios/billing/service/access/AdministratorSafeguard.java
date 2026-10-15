package com.sistemaspalacios.billing.service.access;

import com.sistemaspalacios.billing.repository.RepositorioUsuario;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Impide que el sistema quede sin nadie capaz de administrar accesos (USERS_UPDATE + ROLES_UPDATE).
 * Se aplica el cambio, se sincroniza con la base y se vuelve a contar: si antes había al menos un
 * administrador y después ninguno, se lanza un conflicto y la transacción completa se revierte.
 * Cubre a la vez desactivar o eliminar al último administrador, quitarle el rol, desactivar el rol
 * o retirarle los permisos (incluido el auto-bloqueo del propio administrador).
 */
@Component
public class AdministratorSafeguard {

    private final RepositorioUsuario users;

    public AdministratorSafeguard(RepositorioUsuario users) {
        this.users = users;
    }

    public <T> T guard(Supplier<T> change) {
        users.flush(); // el conteo es SQL nativo: debe ver los cambios pendientes de la transacción
        long before = users.countAccessAdministrators();
        T result = change.get();
        users.flush();
        if (before > 0 && users.countAccessAdministrators() == 0) {
            throw new AccessConflictException("La operación dejaría el sistema sin ningún usuario activo capaz de administrar "
                    + "usuarios y roles. Asigna primero esa capacidad a otro usuario activo.");
        }
        return result;
    }
}
