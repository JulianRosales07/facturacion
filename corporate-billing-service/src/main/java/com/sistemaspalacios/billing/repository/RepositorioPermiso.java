package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.AccessPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Catálogo de permisos (permiso). */
public interface RepositorioPermiso extends JpaRepository<AccessPermission, UUID> {

    @Query("select count(p) > 0 from AccessPermission p where p.code = :code and (:excludedId is null or p.id <> :excludedId)")
    boolean existsCode(@Param("code") String code, @Param("excludedId") UUID excludedId);

    List<AccessPermission> findAllByOrderByModuleAscCodeAsc();

    @Query("select p.code from AccessPermission p where p.active = true order by p.code")
    List<String> findActiveCodes();

    @Query("select p.id, count(r) from AccessRole r join r.permissions p group by p.id")
    List<Object[]> countRolesPerPermission();
}
