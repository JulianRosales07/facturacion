package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.SyncStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for SyncStatusEntity (estado_sincronizacion).
 */
public interface RepositorioEstadoSincronizacion extends JpaRepository<SyncStatusEntity, UUID> {

    Optional<SyncStatusEntity> findTopByOrderByStartedAtDesc();
}
