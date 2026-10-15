package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.InvoicePdfHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository para el histórico de PDF de factura (factura_pdf_historico).
 */
@Repository
public interface RepositorioHistoricoPdfFactura extends JpaRepository<InvoicePdfHistory, UUID> {

    List<InvoicePdfHistory> findByInvoiceIdOrderByArchivedAtDesc(UUID invoiceId);

    List<InvoicePdfHistory> findBySha256Hash(String sha256Hash);

    boolean existsByInvoiceIdAndPdfPath(UUID invoiceId, String pdfPath);
}
