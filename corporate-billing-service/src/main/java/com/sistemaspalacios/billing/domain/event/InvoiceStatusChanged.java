package com.sistemaspalacios.billing.domain.event;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;

import java.util.UUID;

/**
 * Evento de dominio: una factura cambió de estado. Lo registra la propia factura y Spring Data lo
 * publica al guardarla; se notifica a los frontends solo después del commit.
 *
 * @param paymentId     pago que provocó el cambio (null si no lo provocó un pago)
 * @param transactionId transacción de la pasarela asociada (null si no aplica)
 */
public record InvoiceStatusChanged(
        UUID invoiceId,
        String clientNit,
        BillingType billingType,
        String invoiceNumber,
        InvoiceStatus previousStatus,
        InvoiceStatus newStatus,
        UUID paymentId,
        String transactionId
) {}
