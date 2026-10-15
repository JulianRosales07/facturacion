package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.domain.model.Invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Una versión dentro de la cadena de refacturación de una factura (Sprint 10).
 * Permite auditar qué se le cobró al cliente en cada revisión del mismo periodo.
 */
public record FacturaVersionDto(
        UUID id,
        int version,
        boolean vigente,
        String numeroFactura,
        String estado,
        BigDecimal valorTotal,
        BigDecimal diferenciaConVersionAnterior,
        int cantidadItems,
        String cufe,
        String rutaPdf,
        LocalDate fechaEmision,
        String motivoRegeneracion,
        UUID versionAnteriorId,
        UUID reemplazadaPorId,
        Instant reemplazadaEn,
        String creadoPor,
        String regeneradoPor
) {

    public static FacturaVersionDto fromDomain(Invoice invoice, Invoice previous) {
        BigDecimal total = invoice.getTotalAmount() != null
                ? invoice.getTotalAmount().amount()
                : BigDecimal.ZERO;
        BigDecimal delta = null;
        if (previous != null && previous.getTotalAmount() != null) {
            delta = total.subtract(previous.getTotalAmount().amount());
        }

        return new FacturaVersionDto(
                invoice.getId(),
                invoice.getVersionNumber(),
                invoice.isCurrentVersion(),
                invoice.getInvoiceNumber(),
                invoice.getStatus() != null ? invoice.getStatus().getDbValue() : null,
                total,
                delta,
                invoice.getItems() != null ? invoice.getItems().size() : 0,
                invoice.getCufe(),
                // URL del endpoint (PDF recibido o generado por el backend), nunca la ruta física del servidor.
                "/api/v1/facturas/" + invoice.getId() + "/pdf",
                invoice.getIssueDate(),
                invoice.getRegenerationReason(),
                invoice.getPreviousInvoiceId(),
                invoice.getSupersededById(),
                invoice.getSupersededAt(),
                invoice.getCreatedBy(),
                invoice.getRegeneratedBy()
        );
    }

    /**
     * Convierte una cadena ya ordenada por número de versión, calculando la diferencia
     * de valor total entre cada versión y la anterior.
     */
    public static List<FacturaVersionDto> fromChain(List<Invoice> chain) {
        if (chain == null || chain.isEmpty()) {
            return List.of();
        }
        List<FacturaVersionDto> result = new java.util.ArrayList<>(chain.size());
        Invoice previous = null;
        for (Invoice invoice : chain) {
            result.add(fromDomain(invoice, previous));
            previous = invoice;
        }
        return List.copyOf(result);
    }
}
