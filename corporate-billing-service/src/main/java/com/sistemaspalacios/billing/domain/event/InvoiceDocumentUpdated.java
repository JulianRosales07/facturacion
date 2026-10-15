package com.sistemaspalacios.billing.domain.event;

import com.sistemaspalacios.billing.domain.model.BillingType;

import java.util.UUID;

/** Evento de dominio: la factura recibió (o reemplazó) su PDF oficial; su QR puede haber cambiado. */
public record InvoiceDocumentUpdated(
        UUID invoiceId,
        String clientNit,
        BillingType billingType,
        String invoiceNumber
) {}
