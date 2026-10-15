package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.InvoiceExtraField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for InvoiceExtraField (campo_extra_factura).
 */
@Repository
public interface RepositorioCampoExtraFactura extends JpaRepository<InvoiceExtraField, UUID> {

    List<InvoiceExtraField> findByInvoiceId(UUID invoiceId);

    Optional<InvoiceExtraField> findByInvoiceIdAndDefinitionId(UUID invoiceId, UUID definitionId);
}
