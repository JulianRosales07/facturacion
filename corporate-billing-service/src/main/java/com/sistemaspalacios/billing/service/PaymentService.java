package com.sistemaspalacios.billing.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sistemaspalacios.billing.domain.model.Payment;
import com.sistemaspalacios.billing.domain.model.RetentionApplicationMode;
import com.sistemaspalacios.billing.domain.vo.Money;

import java.math.BigDecimal;
import com.sistemaspalacios.billing.domain.vo.PaymentDeduction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Service for Collections and Payments module (Sprint 9, HU-51 to HU-55).
 * <p>
 * simulate, checkout y register liquidan con exactamente la misma lógica (facturas oficiales +
 * conceptos del catálogo + {@code TaxCalculator}); la simulación es informativa y las demás
 * operaciones recalculan siempre en el servidor.
 */
public interface PaymentService {

    /**
     * Simulates payment and tax withholdings settlement without persisting anything.
     */
    PaymentSimulationResult simulate(PaymentSimulationCommand command);

    /**
     * Registro manual confirmado por un analista: persiste el pago APLICADO, cruza las facturas,
     * congela el snapshot de retenciones y deja las facturas en PAGADA.
     */
    PaymentResult register(PaymentRegistrationCommand command);

    /**
     * Checkout de pasarela: recalcula la liquidación y crea el pago PENDIENTE con su snapshot de
     * retenciones. No marca ninguna factura como PAGADA; eso ocurre al confirmar la pasarela.
     */
    CheckoutResult checkout(CheckoutCommand command);

    /**
     * Mismas operaciones registrando quién diligenció las retenciones y desde dónde (auditoría).
     * La hora la fija siempre el servidor; las versiones sin {@code audit} quedan como canal API.
     */
    PaymentSimulationResult simulate(PaymentSimulationCommand command, com.sistemaspalacios.billing.domain.vo.SelectionAudit audit);

    PaymentResult register(PaymentRegistrationCommand command, com.sistemaspalacios.billing.domain.vo.SelectionAudit audit);

    CheckoutResult checkout(CheckoutCommand command, com.sistemaspalacios.billing.domain.vo.SelectionAudit audit);

    /**
     * Confirma un pago desde la pasarela o webhook externo.
     * Con {@code paymentId} confirma el checkout correspondiente (flujo con retenciones);
     * sin él, registra el pago por el valor bruto de las facturas (flujo previo de confirmación directa).
     * Es estrictamente idempotente por transactionId o idempotencyKey.
     */
    PaymentResult confirmPayment(PaymentConfirmationCommand command);

    /**
     * Retrieves payment by ID.
     */
    Payment getPayment(UUID paymentId);

    /** Detalle del pago con su snapshot inmutable de retenciones (panel administrativo). */
    PaymentDetail getPaymentDetail(UUID paymentId);

    /** Pagos (de cualquier estado) que incluyen la factura, del más reciente al más antiguo. */
    List<PaymentDetail> getPaymentDetailsForInvoice(UUID invoiceId);

    /**
     * Generates a printable HTML cash receipt (recibo de caja).
     */
    String getReceiptHtml(UUID paymentId);

    /**
     * Exports Siigo payment flat file layout for accounting integration.
     */
    byte[] exportPaymentFlatFile(UUID paymentId);

    /**
     * Queries client portfolio: pending invoices, outstanding balance, credit note offsets.
     */
    ClientPortfolioSummary getClientPortfolio(String clientNit);

    /**
     * Obtiene los pagos asociados a una factura por su ID.
     */
    List<Payment> getPaymentsForInvoice(UUID invoiceId);

    // --- Command / Result Records ---

    /**
     * Conceptos elegidos para una factura en modo PER_INVOICE.
     * En CONSOLIDATED se usan {@code invoiceIds} + {@code selectedConcepts} y esta lista
     * debe venir vacía; mezclar ambas formas en un mismo pago se rechaza (CA-17).
     */
    record InvoiceRetentionSelection(
            UUID invoiceId,
            @JsonAlias("conceptos") List<ConceptSelection> selectedConcepts
    ) {}

    /**
     * Concepto del catálogo elegido por el cliente con el porcentaje que él ingresó (HU-51).
     * El servidor valida el porcentaje contra el máximo vigente del concepto.
     */
    record ConceptSelection(
            @JsonAlias("conceptoId") UUID conceptId,
            @JsonAlias("porcentaje") BigDecimal percentage
    ) {}

    record PaymentSimulationCommand(
            String clientNit,
            List<UUID> invoiceIds,
            List<ConceptSelection> selectedConcepts,
            List<PaymentDeduction> deductions,
            LocalDate paymentDate,
            RetentionApplicationMode applicationMode,
            List<InvoiceRetentionSelection> invoiceSelections
    ) {
        /** Liquidación consolidada (un solo conjunto de conceptos sobre el total). */
        public PaymentSimulationCommand(String nit, List<UUID> invoiceIds, List<ConceptSelection> concepts,
                List<PaymentDeduction> deductions, LocalDate paymentDate) {
            this(nit, invoiceIds, concepts, deductions, paymentDate, RetentionApplicationMode.CONSOLIDATED, List.of());
        }

        public PaymentSimulationCommand(String nit, List<UUID> invoices, List<ConceptSelection> concepts) {
            this(nit, invoices, concepts, List.of(), LocalDate.now());
        }

        public static PaymentSimulationCommand perInvoice(String nit, List<InvoiceRetentionSelection> selections) {
            return new PaymentSimulationCommand(nit, List.of(), List.of(), List.of(), LocalDate.now(),
                    RetentionApplicationMode.PER_INVOICE, selections);
        }
    }

    record PaymentRegistrationCommand(
            String clientNit,
            List<UUID> invoiceIds,
            List<ConceptSelection> selectedConcepts,
            LocalDate paymentDate,
            String paymentMethod,
            String bankAccount,
            String transactionNumber,
            String observation,
            String idempotencyKey,
            Money expectedNetAmount,
            List<PaymentDeduction> deductions,
            RetentionApplicationMode applicationMode,
            List<InvoiceRetentionSelection> invoiceSelections
    ) {
        /** Registro con retenciones consolidadas. */
        public PaymentRegistrationCommand(String nit, List<UUID> invoices, List<ConceptSelection> concepts, LocalDate date,
                String method, String bank, String transaction, String observation, String idempotencyKey,
                Money expectedNetAmount, List<PaymentDeduction> deductions) {
            this(nit, invoices, concepts, date, method, bank, transaction, observation, idempotencyKey, expectedNetAmount,
                    deductions, RetentionApplicationMode.CONSOLIDATED, List.of());
        }

        public PaymentRegistrationCommand(String nit, List<UUID> invoices, List<ConceptSelection> concepts,
                LocalDate date, String method, String bank, String transaction, String observation) {
            this(nit, invoices, concepts, date, method, bank, transaction, observation, null, null, List.of());
        }
    }

    /**
     * Solo identifica qué se paga, en qué modo y qué conceptos eligió el cliente. Deliberadamente
     * no lleva montos: el neto lo calcula el servidor y cualquier valor enviado por el cliente se ignora.
     */
    record CheckoutCommand(
            String clientNit,
            List<UUID> invoiceIds,
            List<ConceptSelection> selectedConcepts,
            RetentionApplicationMode applicationMode,
            List<InvoiceRetentionSelection> invoiceSelections
    ) {
        public CheckoutCommand(String clientNit, List<UUID> invoiceIds, List<ConceptSelection> selectedConcepts) {
            this(clientNit, invoiceIds, selectedConcepts, RetentionApplicationMode.CONSOLIDATED, List.of());
        }

        public static CheckoutCommand perInvoice(String nit, List<InvoiceRetentionSelection> selections) {
            return new CheckoutCommand(nit, List.of(), List.of(), RetentionApplicationMode.PER_INVOICE, selections);
        }
    }

    record PaymentConfirmationCommand(
            String clientNit,
            String transactionId,
            List<String> invoiceKeys,
            List<UUID> invoiceIds,
            BigDecimal totalAmount,
            LocalDate paymentDate,
            String paymentMethod,
            String bankAccount,
            String authorizationCode,
            String cus,
            String observation,
            String idempotencyKey,
            @JsonAlias({"pagoId", "referencia", "reference"}) UUID paymentId,
            @JsonAlias({"estado", "estadoTransaccion", "transactionStatus"}) String status
    ) {
        public PaymentConfirmationCommand(String clientNit, String transactionId, List<String> invoiceKeys, List<UUID> invoiceIds,
                BigDecimal totalAmount, LocalDate paymentDate, String paymentMethod, String bankAccount,
                String authorizationCode, String cus, String observation, String idempotencyKey) {
            this(clientNit, transactionId, invoiceKeys, invoiceIds, totalAmount, paymentDate, paymentMethod, bankAccount,
                    authorizationCode, cus, observation, idempotencyKey, null, null);
        }

        public PaymentConfirmationCommand(String clientNit, String transactionId, List<String> invoiceKeys, BigDecimal totalAmount) {
            this(clientNit, transactionId, invoiceKeys, List.of(), totalAmount, LocalDate.now(), "PASARELA", "BANCOLOMBIA", null, null, null, null);
        }
    }

    /** Liquidación oficial (HU-51/52). Es la misma estructura en simulación, checkout y registro. */
    record PaymentSimulationResult(
            @JsonProperty("nit") String clientNit,
            @JsonProperty("razonSocial") String clientBusinessName,
            @JsonProperty("facturas") List<SettledInvoice> invoices,
            @JsonProperty("totalFacturas") Money totalInvoices,
            @JsonProperty("baseGravable") Money taxableBase,
            @JsonProperty("totalIva") Money totalIva,
            @JsonProperty("retenciones") List<WithholdingLine> withholdings,
            @JsonProperty("totalRetenciones") Money totalWithholdings,
            @JsonProperty("netoAntesRedondeo") Money netBeforeRounding,
            @JsonProperty("ajusteRedondeo") Money roundingAdjustment,
            @JsonProperty("netoAPagar") Money netPayable,
            @JsonProperty("applicationMode") RetentionApplicationMode applicationMode
    ) {
        public PaymentSimulationResult(String clientNit, String clientBusinessName, List<SettledInvoice> invoices,
                Money totalInvoices, Money taxableBase, Money totalIva, List<WithholdingLine> withholdings,
                Money totalWithholdings, Money netBeforeRounding, Money roundingAdjustment, Money netPayable) {
            this(clientNit, clientBusinessName, invoices, totalInvoices, taxableBase, totalIva, withholdings,
                    totalWithholdings, netBeforeRounding, roundingAdjustment, netPayable, RetentionApplicationMode.CONSOLIDATED);
        }
    }

    /**
     * Factura incluida en la liquidación: valores oficiales y lo que se liquida de ella.
     * En PER_INVOICE lleva sus retenciones y su neto (sin redondear: el redondeo es del pago);
     * en CONSOLIDATED las retenciones van solo a nivel del pago y {@code netoFactura} es nulo.
     */
    record SettledInvoice(
            @JsonProperty("id") UUID invoiceId,
            @JsonProperty("tipo") String billingType,
            @JsonProperty("numero") String invoiceNumber,
            @JsonProperty("fechaEmision") LocalDate issueDate,
            @JsonProperty("fechaVencimiento") LocalDate dueDate,
            @JsonProperty("subtotalOficial") Money officialSubtotal,
            @JsonProperty("ivaOficial") Money officialIva,
            @JsonProperty("totalOficial") Money officialTotal,
            @JsonProperty("notasCredito") Money creditNoteAmount,
            @JsonProperty("abonosPrevios") Money previouslyPaid,
            @JsonProperty("valorLiquidado") Money settledAmount,
            @JsonProperty("baseLiquidada") Money settledTaxableBase,
            @JsonProperty("ivaLiquidado") Money settledIva,
            @JsonProperty("retenciones") List<WithholdingLine> withholdings,
            @JsonProperty("totalRetenciones") Money totalWithholdings,
            @JsonProperty("netoFactura") Money netAmount
    ) {}

    /** Evaluación de un concepto seleccionado: el backend decide si aplica. */
    record WithholdingLine(
            @JsonProperty("conceptoId") UUID conceptId,
            @JsonProperty("nombre") String name,
            @JsonProperty("tipo") String type,
            @JsonProperty("porcentaje") BigDecimal percentage,
            @JsonProperty("version") int version,
            @JsonProperty("baseEvaluada") Money evaluatedBase,
            @JsonProperty("baseMinima") Money minimumBase,
            @JsonProperty("aplica") boolean applicable,
            @JsonProperty("motivo") String reason,
            @JsonProperty("valorRetenido") Money withheldAmount,
            @JsonProperty("facturaId") UUID invoiceId
    ) {
        public WithholdingLine(UUID conceptId, String name, String type, BigDecimal percentage, int version,
                Money evaluatedBase, Money minimumBase, boolean applicable, String reason, Money withheldAmount) {
            this(conceptId, name, type, percentage, version, evaluatedBase, minimumBase, applicable, reason, withheldAmount, null);
        }
    }

    record CheckoutResult(
            @JsonProperty("pagoId") UUID paymentId,
            @JsonProperty("referencia") String reference,
            @JsonProperty("estado") String status,
            @JsonProperty("netoAPagar") Money netPayable,
            @JsonProperty("liquidacion") PaymentSimulationResult settlement
    ) {}

    record PaymentResult(
            UUID paymentId,
            String clientNit,
            LocalDate paymentDate,
            String paymentMethod,
            String bankAccount,
            Money grossAmount,
            Money totalWithholdings,
            Money roundingAmount,
            Money netPaidAmount,
            String transactionNumber,
            List<UUID> paidInvoiceIds,
            boolean partialPayment,
            String partialPaymentNotice,
            int remainingPendingInvoicesCount,
            Money remainingPendingDebt,
            String status,
            Money unappliedAmount
    ) {
        public PaymentResult(UUID paymentId, String clientNit, LocalDate paymentDate, String paymentMethod, String bankAccount,
                Money grossAmount, Money totalWithholdings, Money roundingAmount, Money netPaidAmount, String transactionNumber,
                List<UUID> paidInvoiceIds, boolean partialPayment, String partialPaymentNotice, int remainingPendingInvoicesCount,
                Money remainingPendingDebt, String status) {
            this(paymentId, clientNit, paymentDate, paymentMethod, bankAccount, grossAmount,
                    totalWithholdings, roundingAmount, netPaidAmount, transactionNumber, paidInvoiceIds, partialPayment,
                    partialPaymentNotice, remainingPendingInvoicesCount, remainingPendingDebt, status, Money.ZERO);
        }

        public PaymentResult(UUID paymentId, String clientNit, LocalDate paymentDate, String paymentMethod, String bankAccount,
                Money grossAmount, Money totalWithholdings, Money roundingAmount, Money netPaidAmount, String transactionNumber,
                List<UUID> paidInvoiceIds, boolean partialPayment, String partialPaymentNotice, int remainingPendingInvoicesCount,
                Money remainingPendingDebt) {
            this(paymentId, clientNit, paymentDate, paymentMethod, bankAccount, grossAmount,
                    totalWithholdings, roundingAmount, netPaidAmount, transactionNumber, paidInvoiceIds, partialPayment,
                    partialPaymentNotice, remainingPendingInvoicesCount, remainingPendingDebt, Payment.STATUS_APPLIED, Money.ZERO);
        }
    }

    /** Detalle de un pago tal como quedó registrado; nunca se recalcula desde el catálogo vigente. */
    record PaymentDetail(
            @JsonProperty("pagoId") UUID paymentId,
            @JsonProperty("estado") String status,
            @JsonProperty("nit") String clientNit,
            @JsonProperty("razonSocial") String clientBusinessName,
            @JsonProperty("fechaPago") LocalDate paymentDate,
            @JsonProperty("medioPago") String paymentMethod,
            @JsonProperty("cuentaBanco") String bankAccount,
            @JsonProperty("numeroTransaccion") String transactionNumber,
            @JsonProperty("totalFacturas") Money totalInvoices,
            @JsonProperty("totalRetenciones") Money totalWithholdings,
            @JsonProperty("ajusteRedondeo") Money roundingAdjustment,
            @JsonProperty("netoRecaudado") Money netCollected,
            @JsonProperty("creadoEn") Instant createdAt,
            @JsonProperty("confirmadoEn") Instant confirmedAt,
            @JsonProperty("registradoPor") String createdBy,
            @JsonProperty("observacion") String observation,
            @JsonProperty("modoAplicacion") RetentionApplicationMode applicationMode,
            @JsonProperty("facturas") List<PaymentInvoiceLine> invoices,
            @JsonProperty("retenciones") List<WithholdingSnapshot> withholdings
    ) {}

    record PaymentInvoiceLine(
            @JsonProperty("facturaId") UUID invoiceId,
            @JsonProperty("numero") String invoiceNumber,
            @JsonProperty("estadoFactura") String invoiceStatus,
            @JsonProperty("valorAplicado") Money appliedAmount
    ) {}

    record WithholdingSnapshot(
            @JsonProperty("conceptoId") UUID conceptId,
            @JsonProperty("nombre") String name,
            @JsonProperty("tipo") String type,
            @JsonProperty("porcentaje") BigDecimal percentage,
            @JsonProperty("porcentajeMaximo") BigDecimal maxPercentage,
            @JsonProperty("version") int version,
            @JsonProperty("vigenteDesde") LocalDate validFrom,
            @JsonProperty("vigenteHasta") LocalDate validTo,
            @JsonProperty("baseCalculada") Money calculationBase,
            @JsonProperty("baseMinima") Money minimumBase,
            @JsonProperty("aplica") boolean applicable,
            @JsonProperty("motivoNoAplica") String nonApplicableReason,
            @JsonProperty("valorRetenido") Money withheldAmount,
            @JsonProperty("motivoAjuste") String adjustmentReason,
            // null = calculada sobre el pago consolidado; con valor = sobre esa factura (PER_INVOICE)
            @JsonProperty("facturaId") UUID invoiceId,
            @JsonProperty("facturaNumero") String invoiceNumber,
            // Auditoría: cuándo se seleccionó y aplicó (hora de Colombia, -05:00), canal y quién.
            @JsonProperty("porcentajeIngresado") BigDecimal enteredPercentage,
            @JsonProperty("estadoSeleccion") String selectionStatus,
            @JsonProperty("seleccionadoEn") java.time.OffsetDateTime selectedAt,
            @JsonProperty("aplicadoEn") java.time.OffsetDateTime appliedAt,
            @JsonProperty("canal") String sourceChannel,
            @JsonProperty("seleccionadoPor") String selectedBy
    ) {}

    record InvoiceItemSummary(
            UUID invoiceId,
            String invoiceNumber,
            LocalDate issueDate,
            LocalDate dueDate,
            Money totalAmount,
            Money creditNoteAmount,
            Money paidAmount,
            Money pendingBalance,
            String period
    ) {
        public InvoiceItemSummary(
                UUID invoiceId,
                String invoiceNumber,
                LocalDate issueDate,
                LocalDate dueDate,
                Money totalAmount,
                Money creditNoteAmount,
                Money paidAmount,
                Money pendingBalance
        ) {
            this(invoiceId, invoiceNumber, issueDate, dueDate, totalAmount, creditNoteAmount, paidAmount, pendingBalance, null);
        }
    }

    record ClientPortfolioSummary(
            String clientNit,
            String clientBusinessName,
            boolean upToDate,
            int totalPendingInvoices,
            Money totalOutstandingDebt,
            List<InvoiceItemSummary> pendingInvoices,
            String notice
    ) {}
}
