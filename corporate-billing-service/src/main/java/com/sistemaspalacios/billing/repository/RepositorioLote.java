package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.domain.model.ExportBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for ExportBatch (lote_exportacion).
 */
@Repository
public interface RepositorioLote extends JpaRepository<ExportBatch, UUID> {

    List<ExportBatch> findByPeriodId(UUID periodId);

    List<ExportBatch> findByStatus(ExportBatchStatus status);
}
