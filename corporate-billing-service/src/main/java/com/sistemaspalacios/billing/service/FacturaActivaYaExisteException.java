package com.sistemaspalacios.billing.service;

import java.util.UUID;

/**
 * Excepción de negocio lanzada cuando ya existe una factura activa para un cliente y periodo.
 * Respeta el índice de unicidad parcial uq_factura_cliente_periodo_activa (HU-08, Sprint 4).
 */
public class FacturaActivaYaExisteException extends RuntimeException {

    private final String clientNit;
    private final String periodCode;
    private final UUID existingInvoiceId;

    public FacturaActivaYaExisteException(String clientNit, String periodCode, UUID existingInvoiceId) {
        super(String.format("Ya existe una factura activa emitida para el cliente con NIT %s en el periodo %s (Factura ID: %s). Para refacturar debe anularse previamente la anterior.",
                clientNit, periodCode, existingInvoiceId));
        this.clientNit = clientNit;
        this.periodCode = periodCode;
        this.existingInvoiceId = existingInvoiceId;
    }

    public String getClientNit() {
        return clientNit;
    }

    public String getPeriodCode() {
        return periodCode;
    }

    public UUID getExistingInvoiceId() {
        return existingInvoiceId;
    }
}
