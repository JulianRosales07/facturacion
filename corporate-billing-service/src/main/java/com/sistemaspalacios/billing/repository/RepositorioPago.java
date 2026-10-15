package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for Payment (pago).
 */
public interface RepositorioPago extends JpaRepository<Payment, UUID> {

    java.util.Optional<Payment> findByIdempotencyKey(String key);
    java.util.Optional<Payment> findByPaymentMethodAndBankAccountAndTransactionNumber(String method, String bank, String transaction);
    java.util.Optional<Payment> findByTransactionNumber(String transactionNumber);

    List<Payment> findByClientIdOrderByPaymentDateDesc(UUID clientId);

    @Query("SELECT p FROM Payment p, CorporateClient c WHERE p.clientId = c.id AND c.nit = :nit ORDER BY p.paymentDate DESC")
    List<Payment> findByClientNit(@Param("nit") String nit);

    @Query("SELECT p FROM Payment p JOIN p.invoiceCrossings c WHERE c.invoiceId = :invoiceId")
    List<Payment> findPaymentsByInvoiceId(@Param("invoiceId") UUID invoiceId);

    /** Solo los pagos confirmados cubren una factura; un checkout PENDIENTE o RECHAZADO no abona nada. */
    @Query("SELECT p FROM Payment p JOIN p.invoiceCrossings c WHERE c.invoiceId = :invoiceId AND p.status = 'APLICADO' ORDER BY p.paymentDate DESC")
    List<Payment> findAppliedPaymentsByInvoiceId(@Param("invoiceId") UUID invoiceId);

    @Query("SELECT DISTINCT p FROM Payment p JOIN p.invoiceCrossings c WHERE c.invoiceId IN :invoiceIds AND p.status = 'PENDIENTE'")
    List<Payment> findPendingPaymentsByInvoiceIds(@Param("invoiceIds") java.util.Collection<UUID> invoiceIds);

    @Query("SELECT DISTINCT p FROM Payment p JOIN p.invoiceCrossings c WHERE c.invoiceId = :invoiceId ORDER BY p.createdAt DESC")
    List<Payment> findAllPaymentsByInvoiceIdNewestFirst(@Param("invoiceId") UUID invoiceId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    java.util.Optional<Payment> findByIdForUpdate(@Param("id") UUID id);
}
