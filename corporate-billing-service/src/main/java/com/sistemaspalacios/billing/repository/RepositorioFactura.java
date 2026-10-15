package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Spring Data JPA Repository for Invoice (factura).
 */
@Repository
public interface RepositorioFactura extends JpaRepository<Invoice, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Invoice> {

    /**
     * Estados que NO cuentan como factura vigente. Debe mantenerse alineado con el
     * predicado del índice parcial {@code uq_factura_cliente_periodo_activa}
     * ({@code estado <> 'ANULADA' AND estado <> 'REEMPLAZADA'}).
     */
    Set<InvoiceStatus> NON_CURRENT_STATUSES = Set.of(InvoiceStatus.VOIDED, InvoiceStatus.SUPERSEDED);

    @Query("SELECT f FROM Invoice f WHERE f.clientId = :clientId AND f.periodId = :periodId AND f.status NOT IN :excludedStatuses AND f.documentOrigin = 'INTERNO'")
    Optional<Invoice> findCurrentByClientAndPeriod(
            @Param("clientId") UUID clientId,
            @Param("periodId") UUID periodId,
            @Param("excludedStatuses") Collection<InvoiceStatus> excludedStatuses
    );

    /**
     * Factura vigente del cliente para el periodo: la única que no está anulada
     * ni reemplazada por una versión posterior.
     */
    default Optional<Invoice> findCurrentByClientAndPeriod(UUID clientId, UUID periodId) {
        return findCurrentByClientAndPeriod(clientId, periodId, NON_CURRENT_STATUSES);
    }

    /**
     * Igual que {@link #findCurrentByClientAndPeriod(UUID, UUID)} bloqueando la fila hasta el
     * fin de la transacción: dos procesos que incorporan novedades a la misma factura se
     * ejecutan uno detrás del otro, sin perder cambios.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Invoice f WHERE f.clientId = :clientId AND f.periodId = :periodId AND f.status NOT IN :excludedStatuses AND f.documentOrigin = 'INTERNO'")
    Optional<Invoice> lockCurrentByClientAndPeriod(
            @Param("clientId") UUID clientId,
            @Param("periodId") UUID periodId,
            @Param("excludedStatuses") Collection<InvoiceStatus> excludedStatuses
    );

    default Optional<Invoice> lockCurrentByClientAndPeriod(UUID clientId, UUID periodId) {
        return lockCurrentByClientAndPeriod(clientId, periodId, NON_CURRENT_STATUSES);
    }

    /**
     * @deprecated usar {@link #findCurrentByClientAndPeriod(UUID, UUID)}. Se conserva
     * porque el significado de "activa" cambió al introducirse el versionado:
     * ahora también excluye las facturas reemplazadas.
     */
    @Deprecated(forRemoval = false)
    default Optional<Invoice> findActiveByClientAndPeriod(UUID clientId, UUID periodId) {
        return findCurrentByClientAndPeriod(clientId, periodId);
    }

    // --- Cadena de versiones (Sprint 10) ---------------------------------------

    /** Todas las versiones de una cadena de refacturación, de la 1 a la vigente. */
    List<Invoice> findByOriginInvoiceIdOrderByVersionNumberAsc(UUID originInvoiceId);

    @Query("SELECT COALESCE(MAX(f.versionNumber), 0) FROM Invoice f WHERE f.originInvoiceId = :originInvoiceId")
    int findMaxVersionNumberByOrigin(@Param("originInvoiceId") UUID originInvoiceId);

    Optional<Invoice> findByOriginInvoiceIdAndVersionNumber(UUID originInvoiceId, int versionNumber);

    long countByDocumentOrigin(String origin);
    @Query("SELECT COUNT(f) FROM Invoice f WHERE f.documentOrigin = 'SIIGO' AND f.pdfPath IS NOT NULL AND f.pdfPath <> ''")
    long countReceivedWithPdf();

    Optional<Invoice> findByExternalDocumentKey(String key);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Invoice f WHERE f.externalDocumentKey = :key")
    Optional<Invoice> findByExternalDocumentKeyForUpdate(@Param("key") String key);

    @Query("SELECT f FROM Invoice f WHERE f.billingType = :billingType AND f.status <> com.sistemaspalacios.billing.domain.model.InvoiceStatus.VOIDED AND f.invoiceNumber LIKE CONCAT('%', :consecutive)")
    List<Invoice> findCandidatesByBillingTypeAndConsecutive(
            @Param("billingType") com.sistemaspalacios.billing.domain.model.BillingType billingType,
            @Param("consecutive") String consecutive
    );

    @Query("SELECT f FROM Invoice f WHERE (:billingType IS NULL OR f.billingType = :billingType) AND f.status <> com.sistemaspalacios.billing.domain.model.InvoiceStatus.VOIDED AND f.invoiceNumber LIKE CONCAT('%', :consecutive)")
    List<Invoice> findCandidatesByOptionalTypeAndConsecutive(
            @Param("billingType") com.sistemaspalacios.billing.domain.model.BillingType billingType,
            @Param("consecutive") String consecutive
    );

    @Query("SELECT f FROM Invoice f WHERE f.clientNit = :nit AND f.billingType = :billingType AND f.invoiceNumber LIKE CONCAT('%', :consecutive)")
    List<Invoice> findByClientNitAndBillingTypeAndConsecutive(
            @Param("nit") String nit,
            @Param("billingType") com.sistemaspalacios.billing.domain.model.BillingType billingType,
            @Param("consecutive") String consecutive
    );

    @Query("SELECT f FROM Invoice f WHERE f.clientNit = :nit AND f.invoiceNumber LIKE CONCAT('%', :consecutive)")
    List<Invoice> findByClientNitAndConsecutive(
            @Param("nit") String nit,
            @Param("consecutive") String consecutive
    );

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Invoice f WHERE f.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") UUID id);

    // --- Consultas existentes --------------------------------------------------

    List<Invoice> findByClientId(UUID clientId);

    List<Invoice> findByExportBatchId(UUID exportBatchId);

    List<Invoice> findByPeriodId(UUID periodId);

    List<Invoice> findByPeriodIdAndBillingType(UUID periodId, com.sistemaspalacios.billing.domain.model.BillingType billingType);

    List<Invoice> findByStatus(InvoiceStatus status);

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    Optional<Invoice> findByCufe(String cufe);

    List<Invoice> findByClientNit(String clientNit);

    /**
     * Devuelve una lista porque, con el versionado, un mismo NIT y periodo puede tener
     * varias filas: la vigente más todas las versiones reemplazadas y anuladas.
     */
    List<Invoice> findByClientNitAndPeriodId(String clientNit, UUID periodId);
}
