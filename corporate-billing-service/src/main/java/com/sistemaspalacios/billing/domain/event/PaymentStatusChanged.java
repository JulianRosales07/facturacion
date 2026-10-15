package com.sistemaspalacios.billing.domain.event;

import java.util.List;
import java.util.UUID;

/**
 * Evento de dominio: un pago se creó o cambió de estado (PENDIENTE, APLICADO, RECHAZADO, ANULADO).
 *
 * @param previousStatus null cuando el pago se acaba de crear
 */
public record PaymentStatusChanged(
        UUID paymentId,
        UUID clientId,
        String transactionId,
        String previousStatus,
        String newStatus,
        List<UUID> invoiceIds
) {}
