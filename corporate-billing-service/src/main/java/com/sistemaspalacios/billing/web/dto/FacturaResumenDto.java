package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Resumen de factura para listados, filtros de cartera y vistas generales (Sprint 4, Sprint 9).
 */
public record FacturaResumenDto(
        UUID id,
        String numeroFactura,
        String periodoCodigo,
        String nit,
        String razonSocial,
        boolean nombreDisponible,
        BillingType tipoFacturacion,
        String tipoCorte,
        int sedesCount,
        BigDecimal valorTotal,
        BigDecimal valorNotaCredito,
        BigDecimal saldoPendiente,
        BigDecimal netoPagar,
        String estado,
        LocalDate fechaEmision,
        LocalDate fechaVencimiento,
        boolean esResidencial,
        /** Versión dentro de la cadena de refacturación; 1 en la primera emisión. */
        int version,
        /** {@code true} si es la versión que el cliente debe pagar hoy. */
        boolean vigente,
        /** Saldo según el corte de cartera de Siigo, la fuente de verdad. {@code null} si nunca llegó un corte. */
        BigDecimal saldoSiigo,
        /** Saldo que calcula el microservicio. Se expone para conciliar, no para cobrar. */
        BigDecimal saldoCalculado,
        String origenDocumento,
        @com.fasterxml.jackson.annotation.JsonProperty("estado_pago") String estadoPago,
        @com.fasterxml.jackson.annotation.JsonProperty("fecha_pago") LocalDate fechaPago,
        @com.fasterxml.jackson.annotation.JsonProperty("referencia_pago") String referenciaPago,
        @com.fasterxml.jackson.annotation.JsonProperty("transaction_id") String transactionId
) {
    public static FacturaResumenDto fromDomain(Invoice invoice, String periodCode) {
        boolean nameAvailable = invoice.getClientBusinessName() != null
                && !invoice.getClientBusinessName().isBlank()
                && !invoice.getClientBusinessName().equalsIgnoreCase("SIN_NOMBRE")
                && !invoice.getClientBusinessName().endsWith("(razón social pendiente)");
        String effectiveName = nameAvailable ? invoice.getClientBusinessName() : invoice.getClientNit();

        BigDecimal total = invoice.getTotalAmount() != null ? invoice.getTotalAmount().amount() : BigDecimal.ZERO;
        BigDecimal cn = invoice.getCreditNoteAmount() != null ? invoice.getCreditNoteAmount().amount() : BigDecimal.ZERO;
        BigDecimal pending = invoice.getPendingBalance() != null ? invoice.getPendingBalance().amount() : total;

        String estadoPago = invoice.getStatus() == com.sistemaspalacios.billing.domain.model.InvoiceStatus.PAID ? "PAGADA" :
                (invoice.getStatus() == com.sistemaspalacios.billing.domain.model.InvoiceStatus.VOIDED ? "ANULADA" : "PENDIENTE");

        return new FacturaResumenDto(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                periodCode,
                invoice.getClientNit(),
                effectiveName,
                nameAvailable,
                invoice.getBillingType(),
                "ADELANTADO",
                invoice.getItems() != null ? invoice.getItems().size() : 0,
                total,
                cn,
                pending,
                pending,
                invoice.getStatus() != null ? invoice.getStatus().getDbValue() : "BORRADOR",
                invoice.getIssueDate(),
                invoice.getDueDate(),
                invoice.isResidential(),
                invoice.getVersionNumber(),
                invoice.isCurrentVersion(),
                invoice.getSiigoBalance() != null ? invoice.getSiigoBalance().amount() : null,
                invoice.getComputedPendingBalance().amount(),
                invoice.isExternalDocument() ? "SIIGO" : "INTERNO",
                estadoPago,
                null,
                null,
                null
        );
    }
}
