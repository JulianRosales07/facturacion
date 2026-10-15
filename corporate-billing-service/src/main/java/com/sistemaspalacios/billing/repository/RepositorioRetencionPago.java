package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.PaymentWithholding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Snapshots de retenciones de los pagos (retencion_pago), para la consulta de auditoría. */
public interface RepositorioRetencionPago extends JpaRepository<PaymentWithholding, UUID>, JpaSpecificationExecutor<PaymentWithholding> {

    @Query("select w from PaymentWithholding w where w.payment.id = :paymentId order by w.selectedAt, w.conceptCode")
    List<PaymentWithholding> findByPaymentId(@Param("paymentId") UUID paymentId);
}
