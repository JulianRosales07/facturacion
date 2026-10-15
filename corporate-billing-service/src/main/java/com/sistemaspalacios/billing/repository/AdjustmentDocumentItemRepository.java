package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for AdjustmentDocumentItem (item_documento_ajuste).
 */
@Repository
public interface AdjustmentDocumentItemRepository extends JpaRepository<AdjustmentDocumentItem, UUID> {

    List<AdjustmentDocumentItem> findByDocumentId(UUID documentId);

    List<AdjustmentDocumentItem> findByInvoiceItemId(UUID invoiceItemId);
}
