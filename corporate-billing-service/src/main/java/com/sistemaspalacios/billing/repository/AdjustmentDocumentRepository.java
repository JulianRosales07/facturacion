package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;
import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentStatus;
import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for AdjustmentDocument (documento_ajuste).
 */
@Repository
public interface AdjustmentDocumentRepository extends JpaRepository<AdjustmentDocument, UUID> {

    List<AdjustmentDocument> findByInvoiceId(UUID invoiceId);

    List<AdjustmentDocument> findByInvoiceIdAndStatus(UUID invoiceId, AdjustmentDocumentStatus status);

    List<AdjustmentDocument> findByInvoiceIdAndType(UUID invoiceId, AdjustmentDocumentType type);

    Optional<AdjustmentDocument> findByDocumentNumber(String documentNumber);
}
