package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.RetentionAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Historial de interacción con las retenciones (retencion_evento), solo inserción. */
public interface RepositorioEventoRetencion extends JpaRepository<RetentionAuditEvent, UUID> {

    List<RetentionAuditEvent> findBySessionIdOrderByOccurredAtAsc(String sessionId);

    /** Eventos del pago y de las sesiones que lo originaron (incluye lo diligenciado antes del checkout). */
    @Query("""
            select e from RetentionAuditEvent e
            where e.paymentId = :paymentId
               or e.sessionId in (select x.sessionId from RetentionAuditEvent x where x.paymentId = :paymentId)
               or e.sessionId in :sessions
            order by e.occurredAt, e.conceptCode
            """)
    List<RetentionAuditEvent> findForPayment(@Param("paymentId") UUID paymentId, @Param("sessions") Collection<String> sessions);

    /** Historial de varios pagos a la vez (exportación): por pago o por las sesiones de sus retenciones. */
    @Query("select e from RetentionAuditEvent e where e.paymentId in :paymentIds or e.sessionId in :sessions order by e.occurredAt, e.conceptCode")
    List<RetentionAuditEvent> findForPayments(@Param("paymentIds") Collection<UUID> paymentIds, @Param("sessions") Collection<String> sessions);
}
