package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTO completo de detalle de factura consolidada.
 * Compatible con la plantilla visual y gráfica oficial Siigo.
 */
public record FacturaDetalleDto(
        UUID id,
        String numeroFactura,
        String periodoCodigo,
        String tipoFacturacion,
        String tipoCorte,
        String estado,
        LocalDate fechaEmision,
        LocalDate fechaVencimiento,
        String cufe,
        String rutaPdf,
        ClienteDto cliente,
        List<ItemDto> items,
        TotalesDto totales,
        RetencionesDto retenciones,
        String formaPago,
        String observaciones,
        String origenDocumento,
        @com.fasterxml.jackson.annotation.JsonProperty("estado_pago") String estadoPago,
        @com.fasterxml.jackson.annotation.JsonProperty("fecha_pago") LocalDate fechaPago,
        @com.fasterxml.jackson.annotation.JsonProperty("referencia_pago") String referenciaPago,
        @com.fasterxml.jackson.annotation.JsonProperty("transaction_id") String transactionId,
        @com.fasterxml.jackson.annotation.JsonProperty("valor_pagado") BigDecimal valorPagado,
        @com.fasterxml.jackson.annotation.JsonProperty("proveedor_pago") String proveedorPago
) {

    public record ClienteDto(
            String nit,
            String razonSocial,
            String direccionFiscal,
            String ciudad,
            String telefono,
            String emailFacturacion,
            String estrato,
            String vendedor,
            String centroCosto
    ) {}

    public record ItemDto(
            int item,
            String codigo,
            String descripcion,
            BigDecimal cant,
            String und,
            BigDecimal valorUnitario,
            BigDecimal baseGravable,
            BigDecimal porcentajeIva,
            BigDecimal valorIva,
            BigDecimal valorTotal
    ) {}

    public record TotalesDto(
            BigDecimal bruto,
            BigDecimal descuento,
            BigDecimal subtotal,
            BigDecimal iva,
            BigDecimal total
    ) {}

    public record RetencionesDto(
            BigDecimal retefuente,
            BigDecimal reteica,
            BigDecimal reteiva,
            BigDecimal estampillas,
            BigDecimal totalRetenciones,
            BigDecimal netoPagar
    ) {}

    public static FacturaDetalleDto fromDomain(Invoice invoice, String periodCode) {
        ClienteDto clienteDto = new ClienteDto(
                invoice.getClientNit(),
                invoice.getClientBusinessName(),
                invoice.getClientTaxAddress(),
                null,
                null,
                invoice.getClientBillingEmail(),
                null,
                null,
                null
        );

        List<InvoiceItem> items = invoice.getItems();
        List<ItemDto> itemsDto = java.util.stream.IntStream.range(0, items.size())
                .mapToObj(i -> {
                    InvoiceItem item = items.get(i);
                    String codigo = item.getProductCode();
                    return new ItemDto(
                            i + 1,
                            codigo,
                            item.getDescription(),
                            item.getQuantity(),
                            "UN",
                            item.getUnitPrice().amount(),
                            item.getTaxableBase().amount(),
                            item.getIvaPercentage(),
                            item.getIvaValue().amount(),
                            item.getTotalValue().amount()
                    );
                })
                .toList();

        TotalesDto totalesDto = new TotalesDto(
                invoice.getGrossAmount().amount(),
                invoice.getDiscountAmount().amount(),
                invoice.getSubtotalAmount().amount(),
                invoice.getIvaAmount().amount(),
                invoice.getTotalAmount().amount()
        );

        RetencionesDto retencionesDto = new RetencionesDto(
                invoice.getRetefuenteAmount().amount(),
                invoice.getReteicaAmount().amount(),
                invoice.getReteivaAmount().amount(),
                invoice.getEstampillasAmount().amount(),
                invoice.getTotalWithholdings().amount(),
                invoice.getNetPayableAmount().amount()
        );

        String observaciones = invoice.isExternalDocument() ? "Factura emitida por Siigo e importada para consulta." : null;

        return fromDomain(invoice, periodCode, null);
    }

    public static FacturaDetalleDto fromDomain(Invoice invoice, String periodCode, com.sistemaspalacios.billing.domain.model.Payment payment) {
        ClienteDto clienteDto = new ClienteDto(
                invoice.getClientNit(),
                invoice.getClientBusinessName(),
                invoice.getClientTaxAddress(),
                null,
                null,
                invoice.getClientBillingEmail(),
                null,
                null,
                null
        );

        List<InvoiceItem> items = invoice.getItems();
        List<ItemDto> itemsDto = java.util.stream.IntStream.range(0, items.size())
                .mapToObj(i -> {
                    InvoiceItem item = items.get(i);
                    String codigo = item.getProductCode();
                    return new ItemDto(
                            i + 1,
                            codigo,
                            item.getDescription(),
                            item.getQuantity(),
                            "UN",
                            item.getUnitPrice().amount(),
                            item.getTaxableBase().amount(),
                            item.getIvaPercentage(),
                            item.getIvaValue().amount(),
                            item.getTotalValue().amount()
                    );
                })
                .toList();

        TotalesDto totalesDto = new TotalesDto(
                invoice.getGrossAmount().amount(),
                invoice.getDiscountAmount().amount(),
                invoice.getSubtotalAmount().amount(),
                invoice.getIvaAmount().amount(),
                invoice.getTotalAmount().amount()
        );

        RetencionesDto retencionesDto = new RetencionesDto(
                invoice.getRetefuenteAmount().amount(),
                invoice.getReteicaAmount().amount(),
                invoice.getReteivaAmount().amount(),
                invoice.getEstampillasAmount().amount(),
                invoice.getTotalWithholdings().amount(),
                invoice.getNetPayableAmount().amount()
        );

        String observaciones = invoice.isExternalDocument() ? "Factura emitida por Siigo e importada para consulta." : null;

        String estadoPago = invoice.getStatus() == com.sistemaspalacios.billing.domain.model.InvoiceStatus.PAID ? "PAGADA" :
                (invoice.getStatus() == com.sistemaspalacios.billing.domain.model.InvoiceStatus.VOIDED ? "ANULADA" : "PENDIENTE");
        LocalDate fechaPago = null;
        String referenciaPago = null;
        String transactionId = null;
        BigDecimal valorPagado = null;
        String proveedorPago = null;

        if (payment != null) {
            fechaPago = payment.getPaymentDate();
            referenciaPago = payment.getTransactionNumber();
            transactionId = payment.getTransactionNumber();
            valorPagado = payment.getNetPaidAmount() != null ? payment.getNetPaidAmount().amount() : invoice.getTotalAmount().amount();
            proveedorPago = payment.getPaymentMethod();
        } else if (invoice.getStatus() == com.sistemaspalacios.billing.domain.model.InvoiceStatus.PAID) {
            valorPagado = invoice.getTotalAmount().amount();
        }

        return new FacturaDetalleDto(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                periodCode,
                invoice.getBillingType().name(),
                "ADELANTADO",
                invoice.getStatus().getDbValue(),
                invoice.getIssueDate(),
                invoice.getDueDate(),
                invoice.getCufe(),
                // URL del endpoint (PDF recibido o generado por el backend), nunca la ruta física del servidor.
                "/api/v1/facturas/" + invoice.getId() + "/pdf",
                clienteDto,
                itemsDto,
                totalesDto,
                retencionesDto,
                invoice.getPaymentTerm(),
                observaciones,
                invoice.isExternalDocument() ? "SIIGO" : "INTERNO",
                estadoPago,
                fechaPago,
                referenciaPago,
                transactionId,
                valorPagado,
                proveedorPago
        );
    }
}
