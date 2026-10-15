package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.ExtraFieldDefinition;
import com.sistemaspalacios.billing.domain.model.RecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for ExtraFieldDefinition (definicion_campo_extra).
 */
@Repository
public interface RepositorioDefinicionCampoExtra extends JpaRepository<ExtraFieldDefinition, UUID> {

    Optional<ExtraFieldDefinition> findByFieldKey(String fieldKey);

    List<ExtraFieldDefinition> findByStatus(RecordStatus status);

    List<ExtraFieldDefinition> findByStatusAndMandatory(RecordStatus status, boolean mandatory);
}
