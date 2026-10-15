package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for OrderUpdate (novedad_orden).
 */
@Repository
public interface RepositorioNovedad extends JpaRepository<OrderUpdate, UUID> {

    List<OrderUpdate> findBySiteIdAndPeriodId(UUID siteId, UUID periodId);

    List<OrderUpdate> findByPeriodId(UUID periodId);

    List<OrderUpdate> findBySiteId(UUID siteId);

    // --- Trazabilidad de facturación (Sprint 10) --------------------------------

    /** Novedades de un periodo que aún no ha consumido ninguna factura vigente. */
    List<OrderUpdate> findByPeriodIdAndInvoiceIdIsNull(UUID periodId);

    /** Novedades consumidas por una versión concreta de factura. */
    List<OrderUpdate> findByInvoiceId(UUID invoiceId);

    /**
     * Novedades de un periodo que llegaron después de emitirse la factura indicada,
     * es decir las que justifican una nueva versión.
     */
    @Query("""
            SELECT n FROM OrderUpdate n
            WHERE n.periodId = :periodId
              AND n.siteId IN :siteIds
              AND (n.invoiceId IS NULL OR n.invoiceId <> :invoiceId)
            """)
    List<OrderUpdate> findPendingForInvoice(
            @Param("periodId") UUID periodId,
            @Param("siteIds") List<UUID> siteIds,
            @Param("invoiceId") UUID invoiceId
    );

    /**
     * Libera todas las novedades atadas a una factura que dejó de ser vigente. Un ajuste
     * vuelve a quedar pendiente de ajuste; lo demás, pendiente de su periodo.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            UPDATE OrderUpdate n SET n.invoiceId = NULL, n.billedAt = NULL,
                   n.processingStatus = CASE
                       WHEN n.processingStatus = com.sistemaspalacios.billing.domain.model.NoveltyProcessingStatus.AJUSTE_APLICADO
                       THEN com.sistemaspalacios.billing.domain.model.NoveltyProcessingStatus.PENDIENTE_AJUSTE
                       ELSE com.sistemaspalacios.billing.domain.model.NoveltyProcessingStatus.PENDIENTE END
            WHERE n.invoiceId = :invoiceId
            """)
    int releaseByInvoiceId(@Param("invoiceId") UUID invoiceId);

    /** Novedades de las sedes indicadas en los estados indicados (ajustes de periodos anteriores). */
    List<OrderUpdate> findBySiteIdInAndProcessingStatusIn(
            java.util.Collection<UUID> siteIds,
            java.util.Collection<com.sistemaspalacios.billing.domain.model.NoveltyProcessingStatus> statuses);
}
