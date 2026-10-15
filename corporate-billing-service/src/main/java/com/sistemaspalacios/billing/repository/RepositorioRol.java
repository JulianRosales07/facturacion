package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.AccessRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Roles (rol) y su relación con permisos (rol_permiso). */
public interface RepositorioRol extends JpaRepository<AccessRole, UUID> {

    @Query("select count(r) > 0 from AccessRole r where r.name = :name and (:excludedId is null or r.id <> :excludedId)")
    boolean existsName(@Param("name") String name, @Param("excludedId") UUID excludedId);

    List<AccessRole> findAllByOrderByNameAsc();

    @Query("select r from AccessRole r join r.permissions p where p.id = :permissionId order by r.name")
    List<AccessRole> findByPermissionId(@Param("permissionId") UUID permissionId);
}
