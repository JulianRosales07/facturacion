package com.sistemaspalacios.billing.service.dto;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.vo.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Contrato JSON estructurado para el sistema SP Pagos (pasarela de pagos corporativos).
 * Proporciona toda la información requerida para que el cliente liquide y pague su factura:
 * cliente, periodo, fechas, desglose tributario, retenciones, ítems, balance real y URLs del documento.
 */
public record FacturaSpPagosDto(
        UUID id,
        String numeroFactura,
        String prefijo,
        String numeroCompleto,
        String tipoFacturacion,
        String periodo,
        String cufe,
        String estado,
        boolean pagable,
        ClienteSpPagosDto cliente,
        FechasSpPagosDto fechas,
        TotalesSpPagosDto importes,
        RetencionesSpPagosDto retenciones,
        List<ItemSpPagosDto> items,
        EnlacesSpPagosDto enlaces,
        String formaPago,
        String observaciones,
        CarteraClienteSpPagosDto cartera
) {

    public FacturaSpPagosDto(
            UUID id,
            String numeroFactura,
            String prefijo,
            String numeroCompleto,
            String tipoFacturacion,
            String periodo,
            String cufe,
            String estado,
            boolean pagable,
            ClienteSpPagosDto cliente,
            FechasSpPagosDto fechas,
            TotalesSpPagosDto importes,
            RetencionesSpPagosDto retenciones,
            List<ItemSpPagosDto> items,
            EnlacesSpPagosDto enlaces,
            String formaPago,
            String observaciones
    ) {
        this(id, numeroFactura, prefijo, numeroCompleto, tipoFacturacion, periodo, cufe, estado, pagable,
                cliente, fechas, importes, retenciones, items, enlaces, formaPago, observaciones, null);
    }

    public record ClienteSpPagosDto(
            String nit,
            String razonSocial,
            String direccionFiscal,
            String emailFacturacion,
            String telefono
    ) {}

    public record FechasSpPagosDto(
            LocalDate fechaEmision,
            LocalDate fechaVencimiento,
            boolean vencida,
            LocalDate fechaImportacion,
            LocalDate fechaDisponiblePago,
            LocalDate fechaPago
    ) {
        public FechasSpPagosDto(
                LocalDate fechaEmision,
                LocalDate fechaVencimiento,
                boolean vencida
        ) {
            this(fechaEmision, fechaVencimiento, vencida, null, null, null);
        }
    }

    public record TotalesSpPagosDto(
            BigDecimal subtotal,
            BigDecimal descuento,
            BigDecimal iva,
            BigDecimal totalOficial,
            BigDecimal totalNotasCredito,
            BigDecimal totalPagado,
            BigDecimal saldoPendiente,
            String fuenteSaldo,
            BigDecimal carteraAnteriorPendiente,
            BigDecimal saldoTotalPorCobrar
    ) {
        public TotalesSpPagosDto(
                BigDecimal subtotal,
                BigDecimal descuento,
                BigDecimal iva,
                BigDecimal totalOficial,
                BigDecimal totalNotasCredito,
                BigDecimal totalPagado,
                BigDecimal saldoPendiente,
                String fuenteSaldo
        ) {
            this(subtotal, descuento, iva, totalOficial, totalNotasCredito, totalPagado, saldoPendiente, fuenteSaldo,
                    BigDecimal.ZERO, saldoPendiente);
        }
    }

    public record RetencionesSpPagosDto(
            BigDecimal retefuente,
            BigDecimal reteica,
            BigDecimal reteiva,
            BigDecimal estampillas,
            BigDecimal totalRetenciones,
            BigDecimal netoConRetenciones
    ) {}

    public record ItemSpPagosDto(
            int item,
            String codigo,
            String descripcion,
            BigDecimal cantidad,
            String unidad,
            BigDecimal valorUnitario,
            BigDecimal baseGravable,
            BigDecimal porcentajeIva,
            BigDecimal valorIva,
            BigDecimal valorTotal
    ) {}

    public record EnlacesSpPagosDto(
            boolean tienePdf,
            String pdfUrl,
            String representacionGraficaUrl
    ) {}

    public record ObligacionCarteraDto(
            UUID id,
            String tipo,
            String numeroFactura,
            String periodo,
            LocalDate fechaEmision,
            LocalDate fechaVencimiento,
            BigDecimal valorFactura,
            BigDecimal valorPagado,
            BigDecimal saldoPendiente,
            String estado,
            String cufe
    ) {}

    public record CarteraClienteSpPagosDto(
            BigDecimal valorFacturaActual,
            BigDecimal saldoFacturaActual,
            BigDecimal carteraAnteriorPendiente,
            BigDecimal saldoTotalPorCobrar,
            int totalObligacionesPendientes,
            List<ObligacionCarteraDto> obligacionesAnteriores
    ) {}

    public static FacturaSpPagosDto fromDomain(Invoice invoice, String periodCode) {
        return fromDomain(invoice, periodCode, null);
    }

    public static FacturaSpPagosDto fromDomain(Invoice invoice, String periodCode, CarteraClienteSpPagosDto cartera) {
        String number = invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber() : "";
        String prefix = invoice.getBillingType() == BillingType.F20 ? "FEMG" : "FESP";
        String fullNumber = prefix + " " + number;

        ClienteSpPagosDto clienteDto = new ClienteSpPagosDto(
                invoice.getClientNit(),
                invoice.getClientBusinessName(),
                invoice.getClientTaxAddress(),
                invoice.getClientBillingEmail(),
                null
        );

        boolean isVencida = invoice.getDueDate() != null && LocalDate.now().isAfter(invoice.getDueDate());
        LocalDate fechaImportacion = invoice.getCreatedAt() != null
                ? invoice.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                : null;
        LocalDate fechaDisponiblePago = invoice.getAvailableForPaymentAt() != null
                ? invoice.getAvailableForPaymentAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                : fechaImportacion;
        LocalDate fechaPago = invoice.getPaidDate();

        FechasSpPagosDto fechasDto = new FechasSpPagosDto(
                invoice.getIssueDate(),
                invoice.getDueDate(),
                isVencida,
                fechaImportacion,
                fechaDisponiblePago,
                fechaPago
        );

        Money pendingBalance = invoice.getPendingBalance();
        String fuenteSaldo = invoice.hasSiigoBalance() ? "CORTE_CARTERA_SIIGO" : "CALCULADO_SISTEMA";

        BigDecimal carteraAnterior = cartera != null ? cartera.carteraAnteriorPendiente() : BigDecimal.ZERO;
        BigDecimal saldoTotal = cartera != null ? cartera.saldoTotalPorCobrar() : pendingBalance.amount();

        TotalesSpPagosDto totalesDto = new TotalesSpPagosDto(
                invoice.getSubtotalAmount().amount(),
                invoice.getDiscountAmount().amount(),
                invoice.getIvaAmount().amount(),
                invoice.getTotalAmount().amount(),
                invoice.getCreditNoteAmount().amount(),
                invoice.getPaidAmount().amount(),
                pendingBalance.amount(),
                fuenteSaldo,
                carteraAnterior,
                saldoTotal
        );

        RetencionesSpPagosDto retencionesDto = new RetencionesSpPagosDto(
                invoice.getRetefuenteAmount().amount(),
                invoice.getReteicaAmount().amount(),
                invoice.getReteivaAmount().amount(),
                invoice.getEstampillasAmount().amount(),
                invoice.getTotalWithholdings().amount(),
                invoice.getNetPayableAmount().amount()
        );

        List<InvoiceItem> items = invoice.getItems();
        List<ItemSpPagosDto> itemsDto = IntStream.range(0, items.size())
                .mapToObj(i -> {
                    InvoiceItem it = items.get(i);
                    return new ItemSpPagosDto(
                            i + 1,
                            it.getProductCode(),
                            it.getDescription(),
                            it.getQuantity(),
                            "UN",
                            it.getUnitPrice().amount(),
                            it.getTaxableBase().amount(),
                            it.getIvaPercentage(),
                            it.getIvaValue().amount(),
                            it.getTotalValue().amount()
                    );
                })
                .toList();

        boolean tienePdf = invoice.getPdfPath() != null && !invoice.getPdfPath().isBlank();
        String pdfUrl = "/api/v1/facturas/" + invoice.getId() + "/pdf";
        String htmlUrl = "/api/v1/facturas/" + invoice.getId() + "/representacion-grafica";
        EnlacesSpPagosDto enlacesDto = new EnlacesSpPagosDto(tienePdf, pdfUrl, htmlUrl);

        boolean isPayable = invoice.isPayable() && pendingBalance.isPositive();

        String resolvedPeriod = (periodCode != null && !periodCode.isBlank())
                ? periodCode
                : (invoice.getIssueDate() != null ? java.time.YearMonth.from(invoice.getIssueDate()).toString() : null);

        String observaciones = invoice.isExternalDocument()
                ? "Factura emitida en Siigo e importada para pago corporativo."
                : null;

        return new FacturaSpPagosDto(
                invoice.getId(),
                number,
                prefix,
                fullNumber,
                invoice.getBillingType() != null ? invoice.getBillingType().name() : "F1",
                resolvedPeriod,
                invoice.getCufe(),
                invoice.getStatus().getDbValue(),
                isPayable,
                clienteDto,
                fechasDto,
                totalesDto,
                retencionesDto,
                itemsDto,
                enlacesDto,
                invoice.getPaymentTerm(),
                observaciones,
                cartera
        );
    }
}
