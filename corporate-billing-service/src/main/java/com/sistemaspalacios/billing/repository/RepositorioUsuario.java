package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Usuarios del panel (usuario). Los eliminados lógicamente no se consultan para el acceso, pero
 * siguen ocupando su nombre de usuario y correo (índices únicos).
 */
public interface RepositorioUsuario extends JpaRepository<UserAccount, UUID>, JpaSpecificationExecutor<UserAccount> {

    @Query("select u from UserAccount u where u.deleted = false and lower(u.username) = lower(:username)")
    Optional<UserAccount> findByUsername(@Param("username") String username);

    @Query("select u from UserAccount u where u.deleted = false and lower(u.email) = lower(:email)")
    Optional<UserAccount> findByEmail(@Param("email") String email);

    @Query("select u from UserAccount u where u.deleted = false and u.id = :id")
    Optional<UserAccount> findActiveRecord(@Param("id") UUID id);

    @Query("select count(u) > 0 from UserAccount u where lower(u.username) = lower(:username)")
    boolean existsUsername(@Param("username") String username);

    @Query("select count(u) > 0 from UserAccount u where lower(u.email) = lower(:email) and (:excludedId is null or u.id <> :excludedId)")
    boolean existsEmail(@Param("email") String email, @Param("excludedId") UUID excludedId);

    /** Permisos efectivos: unión, sin duplicados, de los permisos activos de los roles activos del usuario. */
    @Query(value = """
            SELECT DISTINCT p.codigo FROM usuario_rol ur
            JOIN rol r ON r.id = ur.rol_id AND r.activo
            JOIN rol_permiso rp ON rp.rol_id = r.id
            JOIN permiso p ON p.id = rp.permiso_id AND p.activo
            WHERE ur.usuario_id = :userId
            ORDER BY p.codigo
            """, nativeQuery = true)
    List<String> findEffectivePermissionCodes(@Param("userId") UUID userId);

    @Query(value = """
            SELECT r.nombre FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id AND r.activo
            WHERE ur.usuario_id = :userId ORDER BY r.nombre
            """, nativeQuery = true)
    List<String> findActiveRoleNames(@Param("userId") UUID userId);

    /**
     * Usuarios que conservan la capacidad de administrar accesos: activos, no bloqueados ni
     * eliminados, y con USERS_UPDATE y ROLES_UPDATE efectivos. Nunca debe llegar a cero.
     */
    @Query(value = """
            SELECT COUNT(*) FROM usuario u
            WHERE u.activo AND NOT u.bloqueado AND NOT u.eliminado
              AND (SELECT COUNT(DISTINCT p.codigo) FROM usuario_rol ur
                   JOIN rol r ON r.id = ur.rol_id AND r.activo
                   JOIN rol_permiso rp ON rp.rol_id = r.id
                   JOIN permiso p ON p.id = rp.permiso_id AND p.activo
                   WHERE ur.usuario_id = u.id AND p.codigo IN ('USERS_UPDATE', 'ROLES_UPDATE')) = 2
            """, nativeQuery = true)
    long countAccessAdministrators();

    @Query("select u from UserAccount u join u.roles r where u.deleted = false and r.id = :roleId order by u.username")
    List<UserAccount> findByRoleId(@Param("roleId") UUID roleId);

    @Query("select r.id, count(u) from UserAccount u join u.roles r where u.deleted = false group by r.id")
    List<Object[]> countUsersPerRole();
}
