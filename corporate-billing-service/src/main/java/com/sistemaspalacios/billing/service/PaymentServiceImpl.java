package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;
import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentStatus;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.Payment;
import com.sistemaspalacios.billing.domain.model.PaymentInvoice;
import com.sistemaspalacios.billing.domain.model.PaymentWithholding;
import com.sistemaspalacios.billing.domain.model.ProviderPaymentStatus;
import com.sistemaspalacios.billing.domain.model.RetentionApplicationMode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.service.TaxCalculator;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.SelectionAudit;
import com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult;
import com.sistemaspalacios.billing.repository.AdjustmentDocumentRepository;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.repository.RepositorioVigenciaConcepto;
import com.sistemaspalacios.billing.service.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.sistemaspalacios.billing.domain.vo.PaymentDeduction;
import com.sistemaspalacios.billing.domain.vo.SelectedWithholding;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of PaymentService (Sprint 9, HU-51 to HU-55).
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final RepositorioPago repositorioPago;
    private final RepositorioFactura repositorioFactura;
    private final RepositorioCliente repositorioCliente;
    private final RepositorioVigenciaConcepto repositorioVigenciaConcepto;
    private final AdjustmentDocumentRepository adjustmentDocumentRepository;
    private final CurrentUserProvider currentUserProvider;
    private final WithholdingAuditService withholdingAudit;
    private final TaxCalculator taxCalculator = new TaxCalculator();
    private com.sistemaspalacios.billing.repository.RepositorioPeriodo repositorioPeriodo;

    @Autowired(required = false)
    void setRepositorioPeriodo(com.sistemaspalacios.billing.repository.RepositorioPeriodo repositorioPeriodo) {
        this.repositorioPeriodo = repositorioPeriodo;
    }

    @Autowired
    public PaymentServiceImpl(
            RepositorioPago repositorioPago,
            RepositorioFactura repositorioFactura,
            RepositorioCliente repositorioCliente,
            RepositorioVigenciaConcepto repositorioVigenciaConcepto,
            AdjustmentDocumentRepository adjustmentDocumentRepository,
            @Autowired(required = false) CurrentUserProvider currentUserProvider,
            WithholdingAuditService withholdingAudit
    ) {
        this.repositorioPago = Objects.requireNonNull(repositorioPago, "repositorioPago cannot be null");
        this.repositorioFactura = Objects.requireNonNull(repositorioFactura, "repositorioFactura cannot be null");
        this.repositorioCliente = Objects.requireNonNull(repositorioCliente, "repositorioCliente cannot be null");
        this.repositorioVigenciaConcepto = Objects.requireNonNull(repositorioVigenciaConcepto, "repositorioVigenciaConcepto cannot be null");
        this.adjustmentDocumentRepository = Objects.requireNonNull(adjustmentDocumentRepository, "adjustmentDocumentRepository cannot be null");
        this.currentUserProvider = currentUserProvider;
        this.withholdingAudit = Objects.requireNonNull(withholdingAudit, "withholdingAudit cannot be null");
    }

    @org.springframework.beans.factory.annotation.Value("${billing.payments.default-allocation-policy:FIFO}")
    private String defaultAllocationPolicy = "FIFO";

    public void setDefaultAllocationPolicy(String defaultAllocationPolicy) {
        this.defaultAllocationPolicy = defaultAllocationPolicy;
    }

    public String getDefaultAllocationPolicy() {
        return defaultAllocationPolicy;
    }

    /**
     * Comparador determinista para aplicación de pagos bajo regla FIFO:
     * 1. Periodo de servicio (mes más antiguo primero).
     * 2. Fecha de vencimiento (más antigua primero, nulls last).
     * 3. Fecha de emisión (más antigua primero, nulls last).
     * 4. Número de factura (consecutivo alfanumérico).
     * 5. UUID de la factura para resolución determinista de empates.
     */
    public static final Comparator<Invoice> FIFO_INVOICE_COMPARATOR = (a, b) -> {
        YearMonth periodA = resolveServicePeriod(a);
        YearMonth periodB = resolveServicePeriod(b);
        int c = periodA.compareTo(periodB);
        if (c != 0) return c;

        LocalDate dueA = a.getDueDate() != null ? a.getDueDate() : LocalDate.MAX;
        LocalDate dueB = b.getDueDate() != null ? b.getDueDate() : LocalDate.MAX;
        c = dueA.compareTo(dueB);
        if (c != 0) return c;

        LocalDate issueA = a.getIssueDate() != null ? a.getIssueDate() : LocalDate.MAX;
        LocalDate issueB = b.getIssueDate() != null ? b.getIssueDate() : LocalDate.MAX;
        c = issueA.compareTo(issueB);
        if (c != 0) return c;

        String numA = a.getInvoiceNumber() != null ? a.getInvoiceNumber() : "";
        String numB = b.getInvoiceNumber() != null ? b.getInvoiceNumber() : "";
        c = numA.compareTo(numB);
        if (c != 0) return c;

        if (a.getId() != null && b.getId() != null) {
            return a.getId().compareTo(b.getId());
        }
        return 0;
    };

    static YearMonth resolveServicePeriod(Invoice inv) {
        if (inv == null) return YearMonth.of(1970, 1);
        Optional<YearMonth> period = OfficialInvoiceDetailService.servicePeriodOf(inv.getItems());
        if (period.isPresent()) return period.get();
        if (inv.getIssueDate() != null) return YearMonth.from(inv.getIssueDate());
        if (inv.getDueDate() != null) return YearMonth.from(inv.getDueDate());
        return YearMonth.of(1970, 1);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentSimulationResult simulate(PaymentSimulationCommand command) {
        return simulate(command, SelectionAudit.unknown());
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentSimulationResult simulate(PaymentSimulationCommand command, SelectionAudit audit) {
        Objects.requireNonNull(command, "command cannot be null");
        RetentionPlan plan = plan(command.applicationMode(), command.invoiceIds(), command.selectedConcepts(),
                command.invoiceSelections());
        Settlement settlement = settle(command.clientNit(), plan, command.deductions(), command.paymentDate(), false);
        // Cada simulación de una sesión deja en el historial lo que la persona seleccionó, cambió o quitó.
        if (audit != null && audit.sessionId() != null) {
            withholdingAudit.recordSimulation(selectedItems(settlement), audit.withClientNit(settlement.client().getNit()));
        }
        return settlement.toResult();
    }

    @Override
    @Transactional
    public PaymentResult register(PaymentRegistrationCommand command) {
        return register(command, SelectionAudit.unknown());
    }

    @Override
    @Transactional
    public PaymentResult register(PaymentRegistrationCommand command, SelectionAudit audit) {
        Objects.requireNonNull(command, "command cannot be null");
        Objects.requireNonNull(command.clientNit(), "clientNit cannot be null");
        RetentionPlan plan = plan(command.applicationMode(), command.invoiceIds(), command.selectedConcepts(),
                command.invoiceSelections());
        if (plan.invoiceIds().isEmpty()) {
            throw new IllegalArgumentException("Debe especificar al menos una factura para pagar");
        }

        CorporateClient client = repositorioCliente.findByNitForUpdate(command.clientNit().trim())
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado con NIT: " + command.clientNit()));

        String method = normalized(command.paymentMethod(), "TRANSFERENCIA");
        String bankAccount = normalized(command.bankAccount(), "BANCOLOMBIA_CORRIENTE");
        String transaction = normalized(command.transactionNumber(), "");
        if (transaction.isBlank() || transaction.length() > 100) throw new IllegalArgumentException("Referencia de transacción obligatoria (máximo 100 caracteres)");
        String fingerprint = fingerprint(command, plan);
        String key = command.idempotencyKey() != null && !command.idempotencyKey().isBlank()
                ? command.idempotencyKey().trim() : hash(method + "|" + bankAccount + "|" + transaction);
        if (key.length() > 128) throw new IllegalArgumentException("Clave de idempotencia demasiado larga");
        Optional<Payment> previous = repositorioPago.findByIdempotencyKey(key);
        if (previous.isEmpty()) previous = repositorioPago.findByPaymentMethodAndBankAccountAndTransactionNumber(method, bankAccount, transaction);
        if (previous.isPresent()) {
            if (!fingerprint.equals(previous.get().getRequestFingerprint())) {
                throw new PaymentConflictException("La referencia de pago ya existe con datos diferentes");
            }
            return paymentResult(previous.get(), client);
        }

        Settlement settlement = settle(client.getNit(), plan, command.deductions(), command.paymentDate(), true);
        WithholdingCalculationResult calcResult = settlement.calculation();

        if (command.expectedNetAmount() != null && !command.expectedNetAmount().equals(calcResult.roundedNetPayable())) {
            throw new PaymentConflictException("El saldo o la liquidación cambiaron. Consulte y confirme nuevamente el valor");
        }
        // Un registro manual confirmado deja sin efecto cualquier checkout de pasarela aún abierto
        // sobre estas facturas: si la pasarela lo confirma después, se reporta como conflicto.
        SelectionAudit actor = withSession(audit, client.getNit());
        cancelPendingCheckouts(settlement.invoiceIds(), "Reemplazado por registro manual del analista", actor);

        String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";
        LocalDate pDate = command.paymentDate() != null ? command.paymentDate() : LocalDate.now();

        Payment payment = new Payment(
                UUID.randomUUID(),
                client.getId(),
                pDate,
                method,
                bankAccount,
                calcResult.grossAmount(),
                calcResult.totalWithholdings(),
                calcResult.roundingDifference(),
                calcResult.roundedNetPayable(),
                transaction,
                command.observation(),
                currentUser
        );
        attachSettlement(payment, settlement, command.deductions(), actor, Instant.now());

        // Cross each invoice with the payment and transition to PAID (HU-53)
        List<UUID> paidInvoiceIds = new ArrayList<>();
        for (Invoice invoice : settlement.invoices()) {
            markInvoicePaid(invoice, settlement.settledAmount(invoice.getId()), payment.getId(), transaction);
            paidInvoiceIds.add(invoice.getId());
        }

        payment.identifyRequest(key, fingerprint);
        Payment savedPayment = repositorioPago.save(payment);
        withholdingAudit.recordInteraction(selectedItems(settlement), actor, savedPayment.getId());
        withholdingAudit.recordLifecycle(savedPayment, actor);
        log.info("Registered payment ID {} for client NIT {} (Net: {}, Invoices: {}) by user '{}'",
                savedPayment.getId(), client.getNit(), savedPayment.getNetPaidAmount(), paidInvoiceIds.size(), currentUser);

        return paymentResult(savedPayment, client);
    }

    @Override
    @Transactional
    public CheckoutResult checkout(CheckoutCommand command) {
        return checkout(command, SelectionAudit.unknown());
    }

    @Override
    @Transactional
    public CheckoutResult checkout(CheckoutCommand command, SelectionAudit audit) {
        Objects.requireNonNull(command, "command cannot be null");
        // Mismo cálculo que la simulación, repetido aquí con las facturas bloqueadas: lo que el
        // navegador haya mostrado o enviado como neto no interviene.
        RetentionPlan plan = plan(command.applicationMode(), command.invoiceIds(), command.selectedConcepts(),
                command.invoiceSelections());
        Settlement settlement = settle(command.clientNit(), plan, List.of(), LocalDate.now(), true);
        WithholdingCalculationResult calc = settlement.calculation();
        String fingerprint = hash("CHECKOUT|" + settlement.client().getNit() + "|" + plan.describe() + "|" + calc.roundedNetPayable());
        SelectionAudit actor = withSession(audit, settlement.client().getNit());

        // Dos pestañas o un reintento con la misma selección reutilizan el checkout abierto; una
        // selección distinta lo reemplaza. Así nunca quedan dos checkouts vivos sobre una factura.
        for (Payment pending : repositorioPago.findPendingPaymentsByInvoiceIds(settlement.invoiceIds())) {
            boolean sameSelection = fingerprint.equals(pending.getRequestFingerprint())
                    && new java.util.HashSet<>(pending.getInvoiceCrossings().stream().map(PaymentInvoice::getInvoiceId).toList())
                    .equals(new java.util.HashSet<>(settlement.invoiceIds()));
            if (sameSelection) {
                // El snapshot original se conserva; solo se registra si la sesión cambió algo.
                withholdingAudit.recordInteraction(selectedItems(settlement), actor, pending.getId());
                log.info("=== CHECKOUT REUTILIZADO === pagoId={} neto={}", pending.getId(), pending.getNetPaidAmount());
                return new CheckoutResult(pending.getId(), pending.getId().toString(), pending.getStatus(),
                        pending.getNetPaidAmount(), settlement.toResult());
            }
        }
        cancelPendingCheckouts(settlement.invoiceIds(), "Reemplazado por un nuevo checkout con otra selección", actor);

        String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "PASARELA";
        Payment payment = Payment.pendingCheckout(settlement.client().getId(), calc.grossAmount(),
                calc.totalWithholdings(), calc.roundingDifference(), calc.roundedNetPayable(), currentUser);
        // Snapshot de la selección confirmada (antes de ir a la pasarela): hora del servidor, canal y actor.
        attachSettlement(payment, settlement, List.of(), actor, Instant.now());
        payment.identifyRequest("CHK-" + payment.getId(), fingerprint);
        Payment saved = repositorioPago.save(payment);
        withholdingAudit.recordInteraction(selectedItems(settlement), actor, saved.getId());
        withholdingAudit.recordLifecycle(saved, actor);

        log.info("=== CHECKOUT CREADO === pagoId={} NIT={} facturas={} totalFacturas={} retenciones={} ajuste={} netoAPagar={}",
                saved.getId(), settlement.client().getNit(), settlement.invoiceIds().size(), calc.grossAmount(),
                calc.totalWithholdings(), calc.roundingDifference(), calc.roundedNetPayable());
        return new CheckoutResult(saved.getId(), saved.getId().toString(), saved.getStatus(),
                saved.getNetPaidAmount(), settlement.toResult());
    }

    /** Confirmación de la pasarela para un checkout existente (HU-51/53): es la que marca PAGADA. */
    private PaymentResult confirmCheckout(PaymentConfirmationCommand command) {
        UUID paymentId = command.paymentId();
        Payment payment = repositorioPago.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new NoSuchElementException("Pago no encontrado con id: " + paymentId));
        ProviderPaymentStatus outcome = ProviderPaymentStatus.fromProvider(command.status());
        if (outcome == ProviderPaymentStatus.APPROVED && (command.totalAmount() == null
                || Money.of(command.totalAmount()).compareTo(payment.getNetPaidAmount()) != 0)) {
            throw new PaymentConflictException("La aprobación requiere el importe exacto del checkout");
        }
        String txId = command.transactionId() != null && !command.transactionId().isBlank() ? command.transactionId().trim() : null;
        CorporateClient client = repositorioCliente.findById(payment.getClientId())
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado para el pago: " + paymentId));
        log.info("=== CONFIRMANDO CHECKOUT === pagoId={} estadoPago={} estadoProveedor={} -> {} transactionId={} monto={}",
                paymentId, payment.getStatus(), command.status(), outcome, txId, command.totalAmount());

        if (payment.isApplied()) {
            // Idempotencia: la misma notificación repetida no duplica pago, cruces ni retenciones.
            if (txId == null || txId.equals(payment.getTransactionNumber())) {
                log.info("Checkout {} ya estaba APLICADO con la transacción {}; respuesta idempotente", paymentId, payment.getTransactionNumber());
                return paymentResult(payment, client);
            }
            throw new PaymentConflictException("El pago " + paymentId + " ya fue confirmado con la transacción "
                    + payment.getTransactionNumber() + "; la transacción " + txId + " requiere revisión");
        }
        if (!payment.isPending()) {
            log.error("Confirmación {} recibida para el pago {} en estado {}: requiere revisión/devolución", outcome, paymentId, payment.getStatus());
            throw new PaymentConflictException("El pago " + paymentId + " está " + payment.getStatus()
                    + " y no puede confirmarse. Si la pasarela cobró, la transacción " + txId + " requiere devolución");
        }
        if (outcome == ProviderPaymentStatus.PENDING) {
            return paymentResult(payment, client);
        }
        if (txId == null || txId.length() > 100) {
            throw new IllegalArgumentException("transactionId obligatorio (máximo 100 caracteres) para confirmar el pago");
        }
        if (outcome == ProviderPaymentStatus.REJECTED) {
            payment.reject(txId, "Rechazado por la pasarela (" + command.status() + ")");
            repositorioPago.save(payment);
            withholdingAudit.recordLifecycle(payment, providerAudit(payment, client));
            log.warn("=== PAGO RECHAZADO POR LA PASARELA === pagoId={} transactionId={}", paymentId, txId);
            return paymentResult(payment, client);
        }

        // APROBADO: la pasarela debe haber cobrado exactamente el neto calculado por el servidor.
        if (command.totalAmount() != null && Money.of(command.totalAmount()).compareTo(payment.getNetPaidAmount()) != 0) {
            throw new PaymentConflictException("El valor confirmado por la pasarela (" + Money.of(command.totalAmount())
                    + ") no coincide con el neto a pagar del checkout (" + payment.getNetPaidAmount() + ")");
        }
        repositorioPago.findByTransactionNumber(txId).filter(other -> !other.getId().equals(paymentId)).ifPresent(other -> {
            throw new PaymentConflictException("La transacción " + txId + " ya está registrada en el pago " + other.getId());
        });

        List<UUID> invoiceIds = payment.getInvoiceCrossings().stream().map(PaymentInvoice::getInvoiceId).distinct().sorted().toList();
        List<Invoice> invoices = new ArrayList<>();
        for (UUID invoiceId : invoiceIds) {
            Invoice invoice = repositorioFactura.findByIdForUpdate(invoiceId)
                    .orElseThrow(() -> new NoSuchElementException("Factura no encontrada: " + invoiceId));
            if (invoice.getStatus() != InvoiceStatus.CONSOLIDATED) {
                log.error("Checkout {} aprobado por la pasarela pero la factura {} ya está {}: transacción {} requiere devolución",
                        paymentId, invoice.getInvoiceNumber(), invoice.getStatus().getDbValue(), txId);
                throw new PaymentConflictException("La factura " + invoice.getInvoiceNumber() + " ya está "
                        + invoice.getStatus().getDbValue() + "; la transacción " + txId + " requiere devolución");
            }
            enrichInvoiceBalances(invoice);
            invoices.add(invoice);
        }

        log.info("=== ACTUALIZANDO FACTURAS === transactionId={}", txId);
        for (Invoice invoice : invoices) {
            Money applied = payment.getInvoiceCrossings().stream().filter(c -> c.getInvoiceId().equals(invoice.getId()))
                    .map(PaymentInvoice::getAppliedAmount).reduce(Money.ZERO, Money::plus);
            markInvoicePaid(invoice, applied, payment.getId(), txId);
        }
        String method = command.paymentMethod() != null && !command.paymentMethod().isBlank() ? command.paymentMethod().trim() : "PASARELA";
        String bank = command.bankAccount() != null && !command.bankAccount().isBlank() ? command.bankAccount().trim() : "PASARELA";
        payment.confirm(txId, truncate(method, 50), truncate(bank, 50), command.paymentDate());
        if (command.observation() != null && !command.observation().isBlank()) payment.setObservation(command.observation().trim());
        Payment saved = repositorioPago.save(payment);
        withholdingAudit.recordLifecycle(saved, providerAudit(saved, client));
        log.info("=== PAGO PROCESADO CORRECTAMENTE === pagoId={} transactionId={} totalFacturas={} retenciones={} neto={} facturas actualizadas={}",
                saved.getId(), txId, saved.getGrossAmount(), saved.getTotalWithholdings(), saved.getNetPaidAmount(), invoices.size());
        logTransactionOutcome(txId);
        return paymentResult(saved, client);
    }

    // --- Liquidación única (simulación, checkout y registro) ---

    /**
     * Factura liquidada. En PER_INVOICE lleva sus propios conceptos y su cálculo; en CONSOLIDATED
     * ambos son nulos porque las retenciones pertenecen al pago completo y no se reparten.
     */
    private record SettlementLine(Invoice invoice, Money previouslyPaid, Money settledAmount, Money settledBase, Money settledIva,
                                  WithholdingCalculationResult calculation) {}

    private record Settlement(CorporateClient client, RetentionApplicationMode mode, List<SettlementLine> lines,
                              List<TaxConceptValidity> validities, WithholdingCalculationResult calculation) {
        List<Invoice> invoices() { return lines.stream().map(SettlementLine::invoice).toList(); }
        List<UUID> invoiceIds() { return lines.stream().map(l -> l.invoice().getId()).toList(); }
        Money settledAmount(UUID invoiceId) {
            return lines.stream().filter(l -> l.invoice().getId().equals(invoiceId)).map(SettlementLine::settledAmount)
                    .findFirst().orElseThrow();
        }

        PaymentSimulationResult toResult() {
            boolean perInvoice = mode == RetentionApplicationMode.PER_INVOICE;
            List<SettledInvoice> settled = lines.stream().map(l -> new SettledInvoice(
                    l.invoice().getId(),
                    l.invoice().getBillingType() != null ? l.invoice().getBillingType().name() : null,
                    l.invoice().getInvoiceNumber(),
                    l.invoice().getIssueDate(),
                    l.invoice().getDueDate(),
                    l.invoice().getSubtotalAmount(),
                    l.invoice().getIvaAmount(),
                    l.invoice().getTotalAmount(),
                    l.invoice().getCreditNoteAmount(),
                    l.previouslyPaid(),
                    l.settledAmount(),
                    l.settledBase(),
                    l.settledIva(),
                    perInvoice ? l.calculation().evaluations().stream().map(e -> line(e, l.invoice().getId())).toList() : List.of(),
                    perInvoice ? l.calculation().totalWithholdings() : null,
                    perInvoice ? l.settledAmount().minus(l.calculation().totalWithholdings()) : null
            )).toList();
            List<WithholdingLine> withholdings = perInvoice
                    ? lines.stream().flatMap(l -> l.calculation().evaluations().stream().map(e -> line(e, l.invoice().getId()))).toList()
                    : calculation.evaluations().stream().map(e -> line(e, null)).toList();
            return new PaymentSimulationResult(
                    client.getNit(),
                    client.getBusinessName(),
                    settled,
                    calculation.grossAmount(),
                    calculation.totalTaxableBase(),
                    calculation.totalIva(),
                    withholdings,
                    calculation.totalWithholdings(),
                    calculation.rawNetPayable(),
                    calculation.roundingDifference(),
                    calculation.roundedNetPayable(),
                    mode
            );
        }

        private static WithholdingLine line(WithholdingCalculationResult.WithholdingItemEvaluation e, UUID invoiceId) {
            return new WithholdingLine(e.validityId(), e.conceptName(), e.conceptCode().name(), e.percentage(),
                    e.versionNumber(), e.calculationBase(), e.minimumBase(), e.applicable(), e.nonApplicableReason(),
                    e.withheldAmount(), invoiceId);
        }
    }

    /**
     * Selección de retenciones validada: el modo lo envía el cliente y nunca se infiere. En
     * CONSOLIDATED hay un solo conjunto de conceptos; en PER_INVOICE, uno por factura.
     */
    private record RetentionPlan(RetentionApplicationMode mode, List<UUID> invoiceIds, List<ConceptSelection> consolidatedConcepts,
                                 java.util.Map<UUID, List<ConceptSelection>> conceptsByInvoice) {
        private static List<String> describe(List<ConceptSelection> selections) {
            return selections.stream().map(c -> c.conceptId() + "@" + (c.percentage() == null ? "null"
                    : c.percentage().stripTrailingZeros().toPlainString())).sorted().toList();
        }

        /** Representación estable de la selección para la huella de idempotencia. */
        String describe() {
            if (mode == RetentionApplicationMode.CONSOLIDATED) {
                return mode + "|" + invoiceIds.stream().map(UUID::toString).sorted().toList() + "|"
                        + describe(consolidatedConcepts);
            }
            return mode + "|" + conceptsByInvoice.entrySet().stream()
                    .map(e -> e.getKey() + ":" + describe(e.getValue()))
                    .sorted().toList();
        }
    }

    private static RetentionPlan plan(RetentionApplicationMode mode, List<UUID> invoiceIds, List<ConceptSelection> conceptIds,
                                      List<InvoiceRetentionSelection> invoiceSelections) {
        if (mode == null) {
            throw new IllegalArgumentException("Debe indicar applicationMode: PER_INVOICE o CONSOLIDATED");
        }
        boolean consolidatedFields = (invoiceIds != null && !invoiceIds.isEmpty()) || (conceptIds != null && !conceptIds.isEmpty());
        boolean perInvoiceFields = invoiceSelections != null && !invoiceSelections.isEmpty();
        if (consolidatedFields && perInvoiceFields) {
            throw new IllegalArgumentException("No puede combinar retenciones consolidadas (invoiceIds/selectedConceptIds) "
                    + "y retenciones por factura (invoices) en un mismo pago");
        }
        if (mode == RetentionApplicationMode.CONSOLIDATED) {
            if (perInvoiceFields) {
                throw new IllegalArgumentException("En modo CONSOLIDATED envíe invoiceIds y selectedConceptIds; 'invoices' no aplica");
            }
            return new RetentionPlan(mode, invoiceIds != null ? invoiceIds : List.of(),
                    conceptIds != null ? conceptIds : List.of(), java.util.Map.of());
        }
        if (consolidatedFields) {
            throw new IllegalArgumentException("En modo PER_INVOICE envíe la selección de cada factura en 'invoices'");
        }
        java.util.Map<UUID, List<ConceptSelection>> byInvoice = new java.util.LinkedHashMap<>();
        for (InvoiceRetentionSelection selection : invoiceSelections != null ? invoiceSelections : List.<InvoiceRetentionSelection>of()) {
            if (selection == null || selection.invoiceId() == null) throw new IllegalArgumentException("Identificador de factura inválido");
            if (byInvoice.put(selection.invoiceId(), selection.selectedConcepts() != null ? selection.selectedConcepts() : List.<ConceptSelection>of()) != null) {
                throw new IllegalArgumentException("Facturas repetidas en la solicitud");
            }
        }
        return new RetentionPlan(mode, List.copyOf(byInvoice.keySet()), List.of(), byInvoice);
    }

    /**
     * Valida facturas y conceptos y liquida el pago (HU-51/52). Es el único lugar donde se
     * determinan base gravable, IVA y total, y siempre con {@code TaxCalculator}: simulate,
     * checkout y register lo invocan para que nunca puedan divergir (CA-16).
     * <ul>
     *   <li>CONSOLIDATED: bases mínimas sobre la suma de bases; ReteIVA sobre la suma del IVA.</li>
     *   <li>PER_INVOICE: cada factura se evalúa sola con sus conceptos; ReteIVA sobre su propio IVA.</li>
     * </ul>
     */
    private Settlement settle(String nit, RetentionPlan plan, List<PaymentDeduction> deductions,
                              LocalDate paymentDate, boolean lockInvoices) {
        List<UUID> invoiceIds = plan.invoiceIds();
        if (nit == null || nit.isBlank()) throw new IllegalArgumentException("El NIT del cliente es obligatorio");
        if (invoiceIds.isEmpty()) throw new IllegalArgumentException("Debe seleccionar al menos una factura");
        if (invoiceIds.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Identificador de factura inválido");
        if (new java.util.HashSet<>(invoiceIds).size() != invoiceIds.size()) throw new IllegalArgumentException("Facturas repetidas en la solicitud");
        if (plan.mode() == RetentionApplicationMode.PER_INVOICE && deductions != null && !deductions.isEmpty()) {
            throw new IllegalArgumentException("Los ajustes manuales de retención solo se admiten en modo CONSOLIDATED");
        }

        String cleanNit = nit.trim().replaceAll("\\s+", "");
        CorporateClient client = repositorioCliente.findByNit(cleanNit)
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado con NIT: " + cleanNit));

        if (lockInvoices) {
            // Orden estable para evitar interbloqueos entre pagos que comparten facturas.
            invoiceIds.stream().sorted().forEach(id ->
                    repositorioFactura.findByIdForUpdate(id).orElseThrow(() -> new NoSuchElementException("Factura no encontrada: " + id)));
        }
        List<Invoice> invoices = resolveInvoicesForPayment(invoiceIds, client.getId());
        LocalDate date = paymentDate != null ? paymentDate : LocalDate.now();

        List<SettlementLine> lines = new ArrayList<>();
        java.util.Map<UUID, TaxConceptValidity> validities = new java.util.LinkedHashMap<>();
        Money totalInvoices = Money.ZERO;
        Money taxableBase = Money.ZERO;
        Money totalIva = Money.ZERO;
        for (Invoice invoice : invoices) {
            enrichInvoiceBalances(invoice);
            Money pending = requirePayable(invoice);
            boolean selectedWithholdings = !plan.consolidatedConcepts().isEmpty()
                    || !plan.conceptsByInvoice().getOrDefault(invoice.getId(), List.of()).isEmpty()
                    || (deductions != null && !deductions.isEmpty());
            if (!pending.equals(invoice.getTotalAmount()) && selectedWithholdings) {
                throw new PaymentConflictException("Las retenciones sobre un saldo parcial requieren bases conciliadas; no se liquidan con una estimación proporcional");
            }
            Money base;
            Money iva;
            if (pending.equals(invoice.getTotalAmount())) {
                // Factura completamente pendiente: valores oficiales persistidos, sin derivarlos.
                base = invoice.getSubtotalAmount();
                iva = invoice.getIvaAmount();
            } else {
                // Decisión contable pendiente de aprobación por el negocio:
                // En abonos parciales, esta separación proporcional entre base e IVA es estrictamente una estimación
                // operativa/informativa para evaluar topes y retenciones en la pasarela.
                // NO constituye una imputación contable definitiva y los importes oficiales de la factura (subtotal,
                // IVA, total y CUFE) se conservan inalterados.
                // La diferencia exacta 'base = pending.minus(iva)' garantiza la suma de componentes con precisión monetaria (base + iva == pending).
                iva = Money.of(pending.amount().multiply(invoice.getIvaAmount().amount())
                        .divide(invoice.getTotalAmount().amount(), 2, RoundingMode.HALF_UP));
                base = pending.minus(iva);
            }
            WithholdingCalculationResult invoiceCalculation = null;
            if (plan.mode() == RetentionApplicationMode.PER_INVOICE) {
                List<SelectedWithholding> invoiceSelections = resolveSelections(plan.conceptsByInvoice().get(invoice.getId()), date);
                invoiceSelections.forEach(v -> validities.put(v.concept().getId(), v.concept()));
                invoiceCalculation = taxCalculator.settleWithholdings(pending, base, iva, invoiceSelections);
            }
            totalInvoices = totalInvoices.plus(pending);
            taxableBase = taxableBase.plus(base);
            totalIva = totalIva.plus(iva);
            lines.add(new SettlementLine(invoice, invoice.getPaidAmount(), pending, base, iva, invoiceCalculation));
        }

        WithholdingCalculationResult calculation;
        if (plan.mode() == RetentionApplicationMode.PER_INVOICE) {
            calculation = taxCalculator.combinePerInvoice(lines.stream().map(SettlementLine::calculation).toList());
        } else {
            List<SelectedWithholding> selected = resolveSelections(plan.consolidatedConcepts(), date);
            selected.forEach(v -> validities.put(v.concept().getId(), v.concept()));
            calculation = calculateSettlement(totalInvoices, taxableBase, totalIva, selected, deductions);
        }
        return new Settlement(client, plan.mode(), lines, List.copyOf(validities.values()), calculation);
    }

    /** Cruza las facturas y congela el snapshot completo (aplicados y no aplicados) en el pago. */
    private void attachSettlement(Payment payment, Settlement settlement, List<PaymentDeduction> deductions,
                                  SelectionAudit audit, Instant selectedAt) {
        payment.liquidatedWith(settlement.mode());
        for (SettlementLine line : settlement.lines()) {
            // Cada factura se cruza por su obligación completa: recaudo + retenciones aceptadas la cubren.
            payment.addInvoiceCrossing(new PaymentInvoice(UUID.randomUUID(), line.invoice().getId(), line.settledAmount()));
        }
        java.util.Map<UUID, TaxConceptValidity> byId = new java.util.HashMap<>();
        settlement.validities().forEach(v -> byId.put(v.getId(), v));
        if (settlement.mode() == RetentionApplicationMode.PER_INVOICE) {
            for (SettlementLine line : settlement.lines()) {
                for (var item : line.calculation().evaluations()) {
                    payment.addWithholding(snapshot(item, line.invoice().getId(), byId, List.of(), audit, selectedAt));
                }
            }
        } else {
            // Consolidado: la retención pertenece al pago y no se reparte entre facturas.
            for (var item : settlement.calculation().evaluations()) {
                payment.addWithholding(snapshot(item, null, byId, deductions, audit, selectedAt));
            }
        }
        if (!payment.isBalanced()) {
            throw new IllegalStateException("Liquidación descuadrada: totalFacturas " + payment.getGrossAmount()
                    + " distinto de neto " + payment.getNetPaidAmount() + " + retenciones " + payment.getTotalWithholdings()
                    + " - ajuste " + payment.getRoundingAmount());
        }
    }

    private static PaymentWithholding snapshot(WithholdingCalculationResult.WithholdingItemEvaluation item, UUID invoiceId,
                                               java.util.Map<UUID, TaxConceptValidity> validities, List<PaymentDeduction> deductions,
                                               SelectionAudit audit, Instant selectedAt) {
        TaxConceptValidity validity = validities.get(item.validityId());
        PaymentWithholding withholding = PaymentWithholding.snapshotOf(item, invoiceId,
                validity != null ? validity.getMaxPercentage() : null,
                validity != null ? validity.getValidFrom() : null, validity != null ? validity.getValidTo() : null);
        if (deductions != null) deductions.stream()
                .filter(d -> d.validityId().equals(item.validityId())).findFirst()
                .ifPresent(d -> withholding.setAdjustmentReason(d.reason()));
        // El porcentaje ingresado es el que escribió la persona; el sistema lo valida pero no lo transforma.
        withholding.recordSelection(item.percentage(), selectedAt, audit);
        return withholding;
    }

    /** Conceptos tal como se liquidaron: por factura (PER_INVOICE) o del pago completo. */
    private static List<WithholdingAuditService.SelectedItem> selectedItems(Settlement settlement) {
        List<WithholdingAuditService.SelectedItem> items = new ArrayList<>();
        if (settlement.mode() == RetentionApplicationMode.PER_INVOICE) {
            for (SettlementLine line : settlement.lines()) {
                for (var e : line.calculation().evaluations()) items.add(selectedItem(e, line.invoice().getId()));
            }
        } else {
            for (var e : settlement.calculation().evaluations()) items.add(selectedItem(e, null));
        }
        return items;
    }

    private static WithholdingAuditService.SelectedItem selectedItem(WithholdingCalculationResult.WithholdingItemEvaluation e, UUID invoiceId) {
        return new WithholdingAuditService.SelectedItem(invoiceId, e.validityId(), e.conceptCode().name(), e.conceptName(), e.percentage());
    }

    private static java.time.OffsetDateTime businessTime(Instant instant) {
        return instant == null ? null : instant.atZone(WithholdingAuditQueryService.BUSINESS_ZONE).toOffsetDateTime();
    }

    /** Usuario del panel, NIT del cliente en la pasarela, o "No registrado" en pagos anteriores a la auditoría. */
    private static String selectedByLabel(PaymentWithholding w) {
        if (w.getSelectedByUsername() != null) return w.getSelectedByUsername();
        if (w.getSourceChannel() == null) return "No registrado";
        return w.getSelectedByNit() != null ? "NIT " + w.getSelectedByNit() : "No identificado";
    }

    /** Toda selección pertenece a una sesión: si el frontend no envió una, se crea para este pago. */
    private static SelectionAudit withSession(SelectionAudit audit, String clientNit) {
        SelectionAudit base = audit != null ? audit : SelectionAudit.unknown();
        if (base.sessionId() == null) base = base.withSession("SEL-" + UUID.randomUUID());
        return base.withClientNit(clientNit);
    }

    /** La confirmación o el rechazo los informa la pasarela de pagos, no una persona. */
    private static SelectionAudit providerAudit(Payment payment, CorporateClient client) {
        com.sistemaspalacios.billing.domain.model.SourceChannel channel = payment.getAppliedWithholdings().stream()
                .map(PaymentWithholding::getSourceChannel).filter(Objects::nonNull).findFirst()
                .orElse(com.sistemaspalacios.billing.domain.model.SourceChannel.API);
        return new SelectionAudit(channel, null, null, "Pasarela de pagos (confirmación)", client.getNit(), null, null, null);
    }

    private void markInvoicePaid(Invoice invoice, Money applied, UUID paymentId, String transactionId) {
        // enrichInvoiceBalances() dejó en paidAmount solo los pagos previos; se suma el cruce de
        // este pago (neto recaudado + retenciones válidas cubren la obligación completa, CA-9).
        invoice.recordLocalSettlement(applied, Instant.now());
        if (invoice.getComputedPendingBalance().isZero() || invoice.getPendingBalance().isZero()) {
            invoice.markAsPaid(paymentId, transactionId);
            log.info("{}-{} -> PAGADA (aplicado {})", invoice.getBillingType(), invoice.getInvoiceNumber(), applied);
        } else {
            log.info("{}-{} -> ABONO PARCIAL (aplicado {}, saldo pendiente restante {})",
                    invoice.getBillingType(), invoice.getInvoiceNumber(), applied, invoice.getPendingBalance());
        }
        repositorioFactura.save(invoice);
    }

    private void cancelPendingCheckouts(List<UUID> invoiceIds, String reason, SelectionAudit audit) {
        for (Payment pending : repositorioPago.findPendingPaymentsByInvoiceIds(invoiceIds)) {
            pending.cancel(reason);
            repositorioPago.save(pending);
            withholdingAudit.recordLifecycle(pending, audit);
            log.info("Checkout {} ANULADO: {}", pending.getId(), reason);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    @Transactional
    public PaymentResult confirmPayment(PaymentConfirmationCommand command) {
        Objects.requireNonNull(command, "command cannot be null");
        if (command.paymentId() != null) {
            return confirmCheckout(command);
        }
        Objects.requireNonNull(command.clientNit(), "clientNit cannot be null");
        if (command.status() != null && ProviderPaymentStatus.fromProvider(command.status()) != ProviderPaymentStatus.APPROVED) {
            throw new IllegalArgumentException("Sin checkout asociado solo se aceptan confirmaciones aprobadas (estado: " + command.status() + ")");
        }
        String cleanNit = command.clientNit().trim().replaceAll("\\s+", "");

        if (command.transactionId() == null || command.transactionId().isBlank() || command.transactionId().length() > 100
                || command.totalAmount() == null || command.totalAmount().signum() <= 0) {
            throw new IllegalArgumentException("La confirmación requiere referencia de transacción e importe positivo del proveedor");
        }
        String txId = command.transactionId().trim();
        String confirmationFingerprint = hash(cleanNit + "|" + Money.of(command.totalAmount()) + "|"
                + (command.invoiceIds() == null ? List.of() : command.invoiceIds().stream().map(UUID::toString).sorted().toList()) + "|"
                + (command.invoiceKeys() == null ? List.of() : command.invoiceKeys().stream().sorted().toList()) + "|"
                + command.paymentMethod() + "|" + command.bankAccount());
        String idemKey = command.idempotencyKey() != null && !command.idempotencyKey().isBlank()
                ? command.idempotencyKey().trim()
                : txId;

        // 1. Idempotencia: Verificar si esta transacción ya fue registrada previamente
        Optional<Payment> existing = repositorioPago.findByTransactionNumber(txId);
        if (existing.isEmpty() && !idemKey.equals(txId)) {
            existing = repositorioPago.findByIdempotencyKey(idemKey);
        }
        if (existing.isPresent()) {
            Payment prev = existing.get();
            if (!confirmationFingerprint.equals(prev.getRequestFingerprint())) throw new PaymentConflictException("La transacción ya existe con datos diferentes");
            log.info("Transacción {} ya procesada previamente (Idempotencia). Retornando resultado existente sin duplicar.", txId);
            CorporateClient client = repositorioCliente.findById(prev.getClientId()).orElse(null);
            return paymentResult(prev, client);
        }

        // 2. Control de concurrencia: Bloqueo pesimista sobre el cliente para serializar aplicaciones concurrentes
        CorporateClient client = repositorioCliente.findByNitForUpdate(cleanNit).orElse(null);

        // Doble verificación de idempotencia tras adquirir el bloqueo sobre el cliente
        existing = repositorioPago.findByTransactionNumber(txId);
        if (existing.isEmpty() && !idemKey.equals(txId)) {
            existing = repositorioPago.findByIdempotencyKey(idemKey);
        }
        if (existing.isPresent()) {
            Payment prev = existing.get();
            if (!confirmationFingerprint.equals(prev.getRequestFingerprint())) throw new PaymentConflictException("La transacción ya existe con datos diferentes");
            log.info("Transacción {} ya procesada concurrentemente. Retornando resultado existente sin duplicar.", txId);
            return paymentResult(prev, client);
        }

        // 3. Identificar facturas: Explícitas (sin FIFO) vs Generales (FIFO)
        boolean isExplicit = (command.invoiceIds() != null && !command.invoiceIds().isEmpty())
                || (command.invoiceKeys() != null && !command.invoiceKeys().isEmpty());
        List<Invoice> invoicesToPay = new ArrayList<>();

        if (isExplicit) {
            if (command.invoiceIds() != null && !command.invoiceIds().isEmpty()) {
                for (UUID id : command.invoiceIds()) {
                    Invoice inv = repositorioFactura.findByIdForUpdate(id)
                            .orElseThrow(() -> new NoSuchElementException("Factura no encontrada con id: " + id));
                    if (inv.getClientNit() != null) {
                        String invNitClean = inv.getClientNit().replaceAll("[^0-9a-zA-Z]", "");
                        String reqNitClean = cleanNit.replaceAll("[^0-9a-zA-Z]", "");
                        if (!invNitClean.equalsIgnoreCase(reqNitClean) && !inv.getClientNit().replaceAll("\\s+", "").equalsIgnoreCase(cleanNit)) {
                            throw new IllegalArgumentException("La factura " + id + " no pertenece al NIT " + cleanNit);
                        }
                    }
                    invoicesToPay.add(inv);
                }
            } else {
                for (String keyStr : command.invoiceKeys()) {
                    if (keyStr == null || keyStr.isBlank()) continue;
                    String key = keyStr.trim();
                    String typePart = null;
                    String numberPart = key;
                    if (key.contains(":")) {
                        String[] parts = key.split(":", 2);
                        typePart = parts[0].trim();
                        numberPart = parts[1].trim();
                    } else if (key.contains("-")) {
                        String[] parts = key.split("-", 2);
                        typePart = parts[0].trim();
                        numberPart = parts[1].trim();
                    }
                    String cleanNum = IncomingInvoiceQueryService.normalizedNumber(numberPart);
                    com.sistemaspalacios.billing.domain.model.BillingType bType = null;
                    if (typePart != null) {
                        try {
                            bType = com.sistemaspalacios.billing.domain.model.BillingType.valueOf(typePart.toUpperCase());
                        } catch (Exception ignored) {}
                    }
                    List<Invoice> candidates = bType != null
                            ? repositorioFactura.findByClientNitAndBillingTypeAndConsecutive(cleanNit, bType, cleanNum)
                            : repositorioFactura.findByClientNitAndConsecutive(cleanNit, cleanNum);
                    if (candidates.isEmpty()) {
                        Optional<Invoice> byExt = repositorioFactura.findByExternalDocumentKey(key);
                        if (byExt.isPresent() && byExt.get().getClientNit().replaceAll("\\s+", "").equalsIgnoreCase(cleanNit)) {
                            candidates = List.of(byExt.get());
                        }
                    }
                    if (candidates.isEmpty()) {
                        throw new NoSuchElementException("Factura no encontrada para NIT " + cleanNit + " con clave: " + key);
                    }
                    String expectedNumber = cleanNum;
                    candidates = candidates.stream().filter(i -> expectedNumber.equals(IncomingInvoiceQueryService.normalizedNumber(i.getInvoiceNumber()))).toList();
                    if (candidates.size() != 1) throw new PaymentConflictException("La clave de factura es ambigua o no coincide exactamente: " + key);
                    Invoice locked = repositorioFactura.findByIdForUpdate(candidates.get(0).getId())
                            .orElse(candidates.get(0));
                    invoicesToPay.add(locked);
                }
            }
        } else {
            // Pago general: Regla FIFO configurable sobre obligaciones pendientes
            if (!"FIFO".equalsIgnoreCase(defaultAllocationPolicy)) {
                throw new IllegalStateException("Política de asignación de pagos no soportada: " + defaultAllocationPolicy);
            }
            List<Invoice> allClientInvoices = (client != null && client.getId() != null)
                    ? repositorioFactura.findByClientId(client.getId())
                    : repositorioFactura.findByClientNit(cleanNit);

            List<Invoice> pendingCandidates = new ArrayList<>();
            for (Invoice inv : allClientInvoices) {
                if (inv.isCurrentVersion() && inv.getStatus() != InvoiceStatus.VOIDED && inv.getStatus() != InvoiceStatus.SUPERSEDED) {
                    enrichInvoiceBalances(inv);
                    if (inv.getPendingBalance().isPositive()) {
                        pendingCandidates.add(inv);
                    }
                }
            }

            // Orden determinista FIFO: Periodo de servicio -> Vencimiento -> Emisión -> Número -> UUID
            pendingCandidates.sort(FIFO_INVOICE_COMPARATOR);

            for (Invoice cand : pendingCandidates) {
                Invoice locked = repositorioFactura.findByIdForUpdate(cand.getId()).orElse(cand);
                enrichInvoiceBalances(locked);
                invoicesToPay.add(locked);
            }
        }

        if (isExplicit && invoicesToPay.isEmpty()) {
            throw new IllegalArgumentException("No se especificaron facturas válidas para confirmar el pago");
        }
        if (invoicesToPay.stream().map(Invoice::getId).distinct().count() != invoicesToPay.size()) {
            throw new IllegalArgumentException("La confirmación repite una factura");
        }
        log.info("=== FACTURAS LOCALIZADAS === transactionId={} explicit={} facturas={}", txId, isExplicit, invoicesToPay.stream()
                .map(inv -> inv.getBillingType() + "-" + inv.getInvoiceNumber() + " [" + inv.getStatus().getDbValue() + "]")
                .toList());

        // 4. Validar estado de cada factura individual (Control estricto por factura)
        List<Invoice> pendingInvoices = new ArrayList<>();
        for (Invoice inv : invoicesToPay) {
            if (inv.getStatus() == InvoiceStatus.PAID) {
                log.info("Factura {} ({}) ya se encontraba pagada; se omite de nuevo cobro", inv.getInvoiceNumber(), inv.getBillingType());
            } else if (inv.getStatus() == InvoiceStatus.VOIDED || inv.getStatus() == InvoiceStatus.SUPERSEDED) {
                log.warn("Factura {} en estado {}, se omite", inv.getInvoiceNumber(), inv.getStatus());
            } else {
                enrichInvoiceBalances(inv);
                requirePayable(inv);
                pendingInvoices.add(inv);
            }
        }

        if (client == null && !invoicesToPay.isEmpty() && invoicesToPay.get(0).getClientId() != null) {
            client = repositorioCliente.findById(invoicesToPay.get(0).getClientId()).orElse(null);
        }

        if (isExplicit && pendingInvoices.isEmpty()) {
            // En pago explícito, si todas las facturas ya estaban pagadas, se rechaza
            Optional<Payment> lastP = repositorioPago.findAppliedPaymentsByInvoiceId(invoicesToPay.get(0).getId()).stream().findFirst();
            log.warn("Transacción {} intenta pagar facturas explícitas ya PAGADAS (pago previo: {})", txId,
                    lastP.map(Payment::getTransactionNumber).orElse("desconocido"));
            throw new PaymentConflictException("Las facturas indicadas ya están PAGADA"
                    + lastP.map(p -> " por la transacción " + p.getTransactionNumber()).orElse("")
                    + "; la transacción " + txId + " no se aplica");
        } else {
            invoicesToPay = pendingInvoices;
        }

        // 5. Asegurar cliente corporativo y calcular valor total
        if (client == null) {
            String bName = !invoicesToPay.isEmpty() && invoicesToPay.get(0).getClientBusinessName() != null
                    ? invoicesToPay.get(0).getClientBusinessName()
                    : ("EMPRESA NIT " + cleanNit);
            com.sistemaspalacios.billing.domain.model.BillingType bType = !invoicesToPay.isEmpty() && invoicesToPay.get(0).getBillingType() != null
                    ? invoicesToPay.get(0).getBillingType()
                    : com.sistemaspalacios.billing.domain.model.BillingType.F1;
            client = new CorporateClient(
                    UUID.randomUUID(),
                    cleanNit,
                    bName,
                    "CL " + cleanNit,
                    "contacto@" + cleanNit + ".com",
                    "3000000000",
                    (short) 1,
                    (short) 1,
                    bType,
                    false,
                    "BOGOTA",
                    null
            );
            client = repositorioCliente.saveAndFlush(client);
        }

        Money totalGross = Money.ZERO;
        for (Invoice inv : invoicesToPay) {
            enrichInvoiceBalances(inv);
            Money pending = requirePayable(inv);
            totalGross = totalGross.plus(pending);
        }

        UUID clientId = client.getId();
        LocalDate pDate = command.paymentDate() != null ? command.paymentDate() : LocalDate.now();
        String method = command.paymentMethod() != null && !command.paymentMethod().isBlank()
                ? command.paymentMethod().trim() : "PASARELA";
        String bank = command.bankAccount() != null && !command.bankAccount().isBlank()
                ? command.bankAccount().trim() : "BANCOLOMBIA";
        String obs = command.observation() != null && !command.observation().isBlank()
                ? command.observation().trim()
                : ("Recaudo pasarela - " + (command.cus() != null ? "CUS: " + command.cus() : txId));

        Money paymentAmount = (command.totalAmount() != null && Money.of(command.totalAmount()).isPositive())
                ? Money.of(command.totalAmount())
                : totalGross;

        if (invoicesToPay.isEmpty() && !paymentAmount.isPositive()) {
            throw new IllegalArgumentException("No hay facturas pendientes y el monto de pago no es válido");
        }

        Payment payment = new Payment(
                UUID.randomUUID(),
                clientId,
                pDate,
                method,
                bank,
                paymentAmount,
                Money.ZERO,
                Money.ZERO,
                paymentAmount,
                txId,
                obs,
                "PASARELA_RECAUDO"
        );
        payment.identifyRequest(idemKey, confirmationFingerprint);

        // 6. Aplicar importe a las obligaciones y registrar trazabilidad exacta
        log.info("=== APLICANDO PAGO A FACTURAS === transactionId={} facturasCount={}", txId, invoicesToPay.size());
        List<UUID> paidInvoiceIds = new ArrayList<>();
        Money remainingToApply = paymentAmount;

        for (Invoice invoice : invoicesToPay) {
            if (remainingToApply.isZero() || !remainingToApply.isPositive()) {
                break;
            }
            Money pending = requirePayable(invoice);
            Money applied = remainingToApply.compareTo(pending) >= 0 ? pending : remainingToApply;
            if (applied.isPositive()) {
                PaymentInvoice crossing = new PaymentInvoice(
                        UUID.randomUUID(),
                        invoice.getId(),
                        applied
                );
                payment.addInvoiceCrossing(crossing);
                remainingToApply = remainingToApply.minus(applied);
                markInvoicePaid(invoice, applied, payment.getId(), txId);
                paidInvoiceIds.add(invoice.getId());
            }
        }

        // Si el pago supera la cartera pendiente, no se distribuye el excedente a deudas inexistentes;
        // se registra el remanente en observaciones para conciliación / anticipo.
        if (remainingToApply.isPositive()) {
            String excessNotice = " [Excedente no aplicado registrado para conciliación/anticipo: " + remainingToApply + "]";
            String currentObs = payment.getObservation() != null ? payment.getObservation() : "";
            payment.setObservation(truncate(currentObs + excessNotice, 255));
            log.info("Pago {} presenta excedente no aplicado de {} sobre la cartera pendiente. Registrado para conciliación/anticipo.",
                    txId, remainingToApply);
        }

        Payment savedPayment = repositorioPago.save(payment);
        log.info("=== PAGO PROCESADO CORRECTAMENTE === paymentId={} transactionId={} NIT={} facturas actualizadas={}",
                savedPayment.getId(), txId, cleanNit, paidInvoiceIds.size());
        logTransactionOutcome(txId);

        return paymentResult(savedPayment, client);
    }

    /** Deja constancia de si la transacción que marcó las facturas como PAGADA terminó en commit o rollback. */
    private void logTransactionOutcome(String txId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return;
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            log.info("=== COMMIT REALIZADO === transactionId={}", txId);
                        } else {
                            log.error("=== ROLLBACK === transactionId={}: las facturas NO quedaron PAGADA", txId);
                        }
                    }
                });
    }

    private PaymentResult paymentResult(Payment payment, CorporateClient client) {
        List<UUID> paymentInvoiceIds = payment.getInvoiceCrossings().stream().map(PaymentInvoice::getInvoiceId).toList();
        // Partial payment check (HU-53): check if client has remaining unpaid invoices
        List<Invoice> allClientInvoices = (client != null && client.getId() != null)
                ? repositorioFactura.findByClientId(client.getId())
                : List.of();
        int remainingPendingCount = 0;
        Money remainingPendingDebt = Money.ZERO;
        for (Invoice inv : allClientInvoices) {
            enrichInvoiceBalances(inv);
            if (inv.isCurrentVersion() && inv.getStatus() != InvoiceStatus.VOIDED && inv.getStatus() != InvoiceStatus.SUPERSEDED) {
                if (inv.getPendingBalance().isPositive()) {
                    remainingPendingCount++;
                    remainingPendingDebt = remainingPendingDebt.plus(inv.getPendingBalance());
                }
            }
        }

        boolean isPartial = remainingPendingCount > 0;
        String notice = isPartial
                ? String.format("Gracias por su pago. Recuerde que su factura continúa pendiente con saldo por un total de %s (%d factura(s) pendientes).",
                remainingPendingDebt, remainingPendingCount)
                : "Gracias por su pago. Su cartera se encuentra al día.";

        Money totalApplied = payment.getInvoiceCrossings().stream()
                .map(PaymentInvoice::getAppliedAmount)
                .reduce(Money.ZERO, Money::plus);
        Money unapplied = payment.getNetPaidAmount().isGreaterThan(totalApplied)
                ? payment.getNetPaidAmount().minus(totalApplied)
                : Money.ZERO;

        return new PaymentResult(
                payment.getId(),
                client != null ? client.getNit() : (payment.getClientId() != null ? payment.getClientId().toString() : ""),
                payment.getPaymentDate(),
                payment.getPaymentMethod(),
                payment.getBankAccount(),
                payment.getGrossAmount(),
                payment.getTotalWithholdings(),
                payment.getRoundingAmount(),
                payment.getNetPaidAmount(),
                payment.getTransactionNumber(),
                // Un checkout pendiente o rechazado no pagó ninguna factura.
                payment.isApplied() ? paymentInvoiceIds : List.of(),
                isPartial,
                payment.isApplied() ? notice : "El pago está " + payment.getStatus() + "; las facturas siguen pendientes.",
                remainingPendingCount,
                remainingPendingDebt,
                payment.getStatus(),
                unapplied
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Payment getPayment(UUID paymentId) {
        return repositorioPago.findById(paymentId)
                .orElseThrow(() -> new NoSuchElementException("Pago no encontrado con id: " + paymentId));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentDetail getPaymentDetail(UUID paymentId) {
        return toDetail(getPayment(paymentId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentDetail> getPaymentDetailsForInvoice(UUID invoiceId) {
        if (invoiceId == null) return List.of();
        return repositorioPago.findAllPaymentsByInvoiceIdNewestFirst(invoiceId).stream().map(this::toDetail).toList();
    }

    /** Lee exclusivamente el snapshot del pago: el catálogo actual no participa (HU-54, CA-10). */
    private PaymentDetail toDetail(Payment payment) {
        CorporateClient client = repositorioCliente.findById(payment.getClientId()).orElse(null);
        List<PaymentInvoiceLine> invoices = payment.getInvoiceCrossings().stream().map(c -> {
            Invoice invoice = repositorioFactura.findById(c.getInvoiceId()).orElse(null);
            return new PaymentInvoiceLine(
                    c.getInvoiceId(),
                    invoice != null ? invoice.getBillingType() + "-" + invoice.getInvoiceNumber() : null,
                    invoice != null ? invoice.getStatus().getDbValue() : null,
                    c.getAppliedAmount());
        }).toList();
        java.util.Map<UUID, String> invoiceNumbers = new java.util.HashMap<>();
        invoices.forEach(i -> invoiceNumbers.put(i.invoiceId(), i.invoiceNumber()));
        List<WithholdingSnapshot> withholdings = payment.getAppliedWithholdings().stream().map(w -> new WithholdingSnapshot(
                w.getConceptValidityId(),
                w.getConceptName(),
                w.getConceptCode(),
                w.getPercentage(),
                w.getMaxPercentage(),
                w.getVersionNumber(),
                w.getValidFrom(),
                w.getValidTo(),
                w.getCalculationBase(),
                w.getMinimumBase(),
                w.isApplicable(),
                w.getNonApplicableReason(),
                w.getWithheldAmount(),
                w.getAdjustmentReason(),
                w.getInvoiceId(),
                w.getInvoiceId() != null ? invoiceNumbers.get(w.getInvoiceId()) : null,
                w.getEnteredPercentage(),
                w.getSelectionStatus() != null ? w.getSelectionStatus().name() : null,
                businessTime(w.getSelectedAt()),
                businessTime(w.getAppliedAt()),
                w.getSourceChannel() != null ? w.getSourceChannel().name() : null,
                selectedByLabel(w)
        )).toList();
        return new PaymentDetail(
                payment.getId(),
                payment.getStatus(),
                client != null ? client.getNit() : null,
                client != null ? client.getBusinessName() : null,
                payment.getPaymentDate(),
                payment.getPaymentMethod(),
                payment.getBankAccount(),
                payment.getTransactionNumber(),
                payment.getGrossAmount(),
                payment.getTotalWithholdings(),
                payment.getRoundingAmount(),
                payment.getNetPaidAmount(),
                payment.getCreatedAt(),
                payment.getConfirmedAt(),
                payment.getCreatedBy(),
                payment.getObservation(),
                payment.getApplicationMode(),
                invoices,
                withholdings
        );
    }

    /** Retenciones efectivamente descontadas; los conceptos que no aplicaron no generan asiento ni línea de recibo. */
    private static List<PaymentWithholding> effectiveWithholdings(Payment payment) {
        return payment.getAppliedWithholdings().stream()
                .filter(w -> w.isApplicable() && w.getWithheldAmount().isPositive())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String getReceiptHtml(UUID paymentId) {
        Payment payment = getPayment(paymentId);
        CorporateClient client = repositorioCliente.findById(payment.getClientId())
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado para el pago: " + paymentId));

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n<meta charset=\"UTF-8\">\n")
                .append("<title>Recibo de Caja - ").append(payment.getId()).append("</title>\n")
                .append("<style>\n")
                .append("body { font-family: Arial, sans-serif; margin: 40px; color: #222; }\n")
                .append(".header { border-bottom: 2px solid #0056b3; padding-bottom: 10px; margin-bottom: 20px; }\n")
                .append("table { width: 100%; border-collapse: collapse; margin-top: 15px; margin-bottom: 15px; }\n")
                .append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; font-size: 13px; }\n")
                .append("th { background-color: #f2f7fb; color: #0056b3; }\n")
                .append(".total-box { margin-top: 20px; float: right; width: 320px; }\n")
                .append(".total-row { display: flex; justify-content: space-between; padding: 4px 0; font-size: 14px; }\n")
                .append(".grand-total { font-size: 18px; font-weight: bold; border-top: 2px solid #0056b3; padding-top: 6px; }\n")
                .append("</style>\n</head>\n<body>\n")
                .append("<div class=\"header\">\n")
                .append("<h2>SISTEMAS PALACIOS S.A.S. — RECIBO DE CAJA / PAGO</h2>\n")
                .append("<p><strong>ID Comprobante:</strong> ").append(payment.getId()).append(" | <strong>Fecha:</strong> ").append(payment.getPaymentDate()).append("</p>\n")
                .append("<p><strong>Cliente:</strong> ").append(html(client.getBusinessName())).append(" | <strong>NIT:</strong> ").append(html(client.getNit())).append("</p>\n")
                .append("<p><strong>Medio de Pago:</strong> ").append(html(payment.getPaymentMethod())).append(" | <strong>Banco:</strong> ").append(html(payment.getBankAccount())).append(" | <strong>N° Transacción:</strong> ").append(html(payment.getTransactionNumber() != null ? payment.getTransactionNumber() : "N/A")).append("</p>\n")
                .append("</div>\n");

        sb.append("<h3>Facturas Cruzadas en este Pago</h3>\n<table>\n<tr><th>ID Factura</th><th>Valor Aplicado</th></tr>\n");
        for (PaymentInvoice pi : payment.getInvoiceCrossings()) {
            sb.append("<tr><td>").append(pi.getInvoiceId()).append("</td><td>").append(pi.getAppliedAmount()).append("</td></tr>\n");
        }
        sb.append("</table>\n");

        if (!effectiveWithholdings(payment).isEmpty()) {
            sb.append("<h3>Retenciones Fiscales Practicadas (Congeladas)</h3>\n<table>\n<tr><th>Concepto</th><th>Versión</th><th>Tasa</th><th>Base de Cálculo</th><th>Valor Retenido</th></tr>\n");
            for (PaymentWithholding pw : effectiveWithholdings(payment)) {
                sb.append("<tr><td>").append(html(pw.getConceptName())).append("</td><td>v").append(pw.getVersionNumber())
                        .append("</td><td>").append(pw.getPercentage()).append("%</td><td>").append(pw.getCalculationBase())
                        .append("</td><td>").append(pw.getWithheldAmount()).append("</td></tr>\n");
            }
            sb.append("</table>\n");
        }

        sb.append("<div class=\"total-box\">\n")
                .append("<div class=\"total-row\"><span>Total Bruto Facturado:</span><span>").append(payment.getGrossAmount()).append("</span></div>\n")
                .append("<div class=\"total-row\"><span>Total Retenciones:</span><span>-").append(payment.getTotalWithholdings()).append("</span></div>\n")
                .append("<div class=\"total-row\"><span>Ajuste por Redondeo:</span><span>").append(payment.getRoundingAmount()).append("</span></div>\n")
                .append("<div class=\"total-row grand-total\"><span>Neto Recaudado:</span><span>").append(payment.getNetPaidAmount()).append("</span></div>\n")
                .append("</div>\n")
                .append("<div style=\"clear:both;\"></div>\n")
                .append("<p style=\"margin-top:40px; font-size:12px; color:#666;\">Registrado por: ").append(html(payment.getCreatedBy())).append(" | Estado: ").append(payment.getStatus()).append("</p>\n")
                .append("</body>\n</html>");

        return sb.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportPaymentFlatFile(UUID paymentId) {
        Payment payment = getPayment(paymentId);
        CorporateClient client = repositorioCliente.findById(payment.getClientId())
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado para el pago: " + paymentId));

        StringBuilder sb = new StringBuilder();
        String dateStr = payment.getPaymentDate().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // Row 1: Bank Account Debit (D) -> Net Amount Paid
        sb.append(String.join(",",
                "RC", // Tipo comprobante Recibo de Caja
                "1",
                payment.getId().toString().substring(0, 8),
                payment.getBankAccount(),
                "D",
                payment.getNetPaidAmount().getAmount().toPlainString(),
                client.getNit(),
                "RECAUDO " + client.getNit() + " " + dateStr
        )).append("\r\n");

        // Rows 2..N: Withholding Deductions Debit (D)
        for (PaymentWithholding pw : effectiveWithholdings(payment)) {
            String withholdingAccount = resolveWithholdingAccount(pw.getConceptCode());
            sb.append(String.join(",",
                    "RC",
                    "1",
                    payment.getId().toString().substring(0, 8),
                    withholdingAccount,
                    "D",
                    pw.getWithheldAmount().getAmount().toPlainString(),
                    client.getNit(),
                    "RETENCION " + pw.getConceptName()
            )).append("\r\n");
        }

        // Row for Rounding difference if any
        if (!payment.getRoundingAmount().isZero()) {
            String roundNature = payment.getRoundingAmount().isPositive() ? "C" : "D";
            String roundAccount = payment.getRoundingAmount().isPositive() ? "429505" : "539505";
            sb.append(String.join(",",
                    "RC",
                    "1",
                    payment.getId().toString().substring(0, 8),
                    roundAccount,
                    roundNature,
                    payment.getRoundingAmount().getAmount().abs().toPlainString(),
                    client.getNit(),
                    "AJUSTE REDONDEO AL PESO"
            )).append("\r\n");
        }

        // Row Last: Client Cartera Credit (C) -> Total Gross Amount
        sb.append(String.join(",",
                "RC",
                "1",
                payment.getId().toString().substring(0, 8),
                "1305051004", // Cuenta cartera cliente Siigo
                "C",
                payment.getGrossAmount().getAmount().toPlainString(),
                client.getNit(),
                "CRUCE CARTERA CLIENTE " + client.getNit()
        )).append("\r\n");

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional(readOnly = true)
    public ClientPortfolioSummary getClientPortfolio(String clientNit) {
        Objects.requireNonNull(clientNit, "clientNit cannot be null");
        CorporateClient client = repositorioCliente.findByNit(clientNit.trim())
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado con NIT: " + clientNit));

        List<Invoice> invoices = repositorioFactura.findByClientId(client.getId());
        List<InvoiceItemSummary> pendingSummaries = new ArrayList<>();
        Money totalDebt = Money.ZERO;

        for (Invoice invoice : invoices) {
            enrichInvoiceBalances(invoice);
            if (invoice.isCurrentVersion() && !invoice.isFullyPaidOrCrossed()) {
                pendingSummaries.add(new InvoiceItemSummary(
                        invoice.getId(),
                        invoice.getInvoiceNumber(),
                        invoice.getIssueDate(),
                        invoice.getDueDate(),
                        invoice.getTotalAmount(),
                        invoice.getCreditNoteAmount(),
                        invoice.getPaidAmount(),
                        invoice.getPendingBalance(),
                        periodCodeOf(invoice)
                ));
                totalDebt = totalDebt.plus(invoice.getPendingBalance());
            }
        }

        boolean upToDate = pendingSummaries.isEmpty();
        String notice = upToDate
                ? "El cliente no presenta facturas pendientes de pago."
                : String.format("El cliente presenta %d factura(s) pendientes por un valor total adeudado de %s.",
                pendingSummaries.size(), totalDebt);

        return new ClientPortfolioSummary(
                client.getNit(),
                client.getBusinessName(),
                upToDate,
                pendingSummaries.size(),
                totalDebt,
                pendingSummaries,
                notice
        );
    }

    private String periodCodeOf(Invoice invoice) {
        if (repositorioPeriodo != null && invoice.getPeriodId() != null) {
            var period = repositorioPeriodo.findById(invoice.getPeriodId());
            if (period.isPresent()) return period.get().getCode();
        }
        return invoice.getIssueDate() != null ? java.time.YearMonth.from(invoice.getIssueDate()).toString() : null;
    }

    // --- Helper Methods ---

    private Money requirePayable(Invoice invoice) {
        String label = invoice.getBillingType() + "-" + invoice.getInvoiceNumber();
        if (invoice.hasCarteraPlaceholderDetail()) {
            throw new PaymentConflictException("La factura " + label + " requiere detalle oficial confirmado antes de cobrar");
        }
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new PaymentConflictException("La factura " + label + " ya está PAGADA");
        }
        if (invoice.getStatus() != InvoiceStatus.CONSOLIDATED || !invoice.isCurrentVersion()) {
            throw new PaymentConflictException("La factura " + label + " no es cobrable (estado " + invoice.getStatus().getDbValue() + ")");
        }
        if (!invoice.getPendingBalance().isPositive() || !invoice.getTotalAmount().isPositive()) {
            throw new PaymentConflictException("La factura " + label + " no tiene saldo pagable");
        }
        if (!invoice.isPayable()) throw new PaymentConflictException("La factura " + label + " requiere conciliación antes de cobrar");
        return invoice.getPendingBalance();
    }

    private WithholdingCalculationResult calculateSettlement(Money totalInvoices, Money subtotal, Money iva,
            List<SelectedWithholding> selections, List<PaymentDeduction> overrides) {
        var calculated = taxCalculator.settleWithholdings(totalInvoices, subtotal, iva, selections);
        var edits = new java.util.HashMap<UUID, PaymentDeduction>();
        for (PaymentDeduction edit : overrides != null ? overrides : List.<PaymentDeduction>of()) {
            if (edits.put(edit.validityId(), edit) != null || selections.stream().noneMatch(v -> v.concept().getId().equals(edit.validityId()))) {
                throw new IllegalArgumentException("Ajuste repetido o concepto no seleccionado");
            }
        }
        List<WithholdingCalculationResult.WithholdingItemEvaluation> evaluations = new ArrayList<>();
        Money total = Money.ZERO;
        for (var item : calculated.evaluations()) {
            var edit = edits.get(item.validityId());
            if (edit != null) {
                Money withheld = edit.amount() != null ? edit.amount() : item.calculationBase().percentage(edit.percentage());
                if (withheld.compareTo(item.calculationBase()) > 0) throw new IllegalArgumentException("Retención mayor a su base");
                BigDecimal rate = edit.percentage() != null ? edit.percentage()
                        : item.calculationBase().isZero() ? BigDecimal.ZERO
                        : withheld.amount().multiply(new BigDecimal("100")).divide(item.calculationBase().amount(), 6, RoundingMode.HALF_UP);
                item = new WithholdingCalculationResult.WithholdingItemEvaluation(item.validityId(), item.versionNumber(),
                        item.conceptCode(), item.conceptName(), rate, item.minimumBase(), item.calculationBase(), withheld, true, null);
            }
            evaluations.add(item);
            total = total.plus(item.withheldAmount());
        }
        Money gross = calculated.grossAmount();
        if (total.compareTo(gross) > 0) throw new IllegalArgumentException("Las deducciones superan el saldo");
        Money net = gross.minus(total);
        return new WithholdingCalculationResult(gross, subtotal, iva, total, net, net.roundToPeso(),
                net.roundToPeso().minus(net), evaluations);
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String fingerprint(PaymentRegistrationCommand command, RetentionPlan plan) {
        var edits = command.deductions() == null ? List.of() : command.deductions().stream().map(Object::toString).sorted().toList();
        return hash(command.clientNit().trim() + "|" + plan.describe() + "|" + edits + "|"
                + command.paymentDate() + "|" + normalized(command.paymentMethod(), "TRANSFERENCIA") + "|"
                + normalized(command.bankAccount(), "BANCOLOMBIA_CORRIENTE") + "|" + command.transactionNumber().trim()
                + "|" + command.expectedNetAmount());
    }

    private String hash(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private String html(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private List<Invoice> resolveInvoicesForPayment(List<UUID> invoiceIds, UUID clientId) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            // If empty, take all unpaid invoices of client
            return repositorioFactura.findByClientId(clientId).stream()
                    .filter(inv -> inv.getStatus() == InvoiceStatus.CONSOLIDATED)
                    .collect(Collectors.toList());
        }

        if (new java.util.HashSet<>(invoiceIds).size() != invoiceIds.size()) throw new IllegalArgumentException("Facturas repetidas en la solicitud");
        List<Invoice> invoices = new ArrayList<>();
        for (UUID id : invoiceIds) {
            Invoice inv = repositorioFactura.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("Factura no encontrada con id: " + id));
            if (!inv.getClientId().equals(clientId)) {
                throw new IllegalArgumentException("La factura " + id + " no pertenece al cliente especificado");
            }
            if (inv.getStatus() == InvoiceStatus.VOIDED) {
                throw new IllegalStateException("La factura " + id + " se encuentra anulada");
            }
            invoices.add(inv);
        }
        return invoices;
    }

    /**
     * Resuelve los conceptos elegidos con el porcentaje del cliente: deben existir, estar vigentes y
     * no superar el máximo del catálogo (validado en {@link SelectedWithholding}).
     */
    private List<SelectedWithholding> resolveSelections(List<ConceptSelection> selections, LocalDate paymentDate) {
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }
        List<SelectedWithholding> resolved = new ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        LocalDate date = paymentDate != null ? paymentDate : LocalDate.now();
        for (ConceptSelection selection : selections) {
            if (selection == null || selection.conceptId() == null) throw new IllegalArgumentException("Concepto tributario inválido");
            UUID id = selection.conceptId();
            if (!seen.add(id)) throw new IllegalArgumentException("Conceptos repetidos");
            TaxConceptValidity validity = repositorioVigenciaConcepto.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Concepto tributario inexistente: " + id));
            if (!validity.isApplicable(date, null, null)) throw new IllegalArgumentException("Concepto tributario fuera de vigencia: " + validity.getName());
            resolved.add(new SelectedWithholding(validity, selection.percentage()));
        }
        SelectedWithholding.requireOnePerType(resolved);
        return resolved;
    }

    private void enrichInvoiceBalances(Invoice invoice) {
        // Compute credit note offsets
        List<AdjustmentDocument> creditNotes = adjustmentDocumentRepository
                .findByInvoiceIdAndStatus(invoice.getId(), AdjustmentDocumentStatus.APPLIED);
        Money totalCn = Money.ZERO;
        for (AdjustmentDocument cn : creditNotes) {
            totalCn = totalCn.plus(cn.getNetAmount());
        }
        invoice.setCreditNoteAmount(totalCn);

        // Compute prior paid amounts
        // Money es inmutable: plus() devuelve una instancia nueva. La versión anterior hacía
        // "totalPaid.plus(...)" dentro de un forEach y descartaba el resultado, por lo que el
        // acumulado se quedaba en cero y ninguna factura reflejaba nunca lo pagado. Eso
        // rompía getPendingBalance(), el filtro soloConSaldo de cartera y el aviso de pago
        // parcial. Se reduce sobre el stream para que el resultado sí se conserve.
        // Solo cuentan pagos APLICADO: un checkout PENDIENTE/RECHAZADO/ANULADO no abona nada.
        List<Payment> payments = repositorioPago.findAppliedPaymentsByInvoiceId(invoice.getId());
        Money totalPaid = Money.ZERO;
        for (Payment p : payments) {
            for (PaymentInvoice crossing : p.getInvoiceCrossings()) {
                if (crossing.getInvoiceId().equals(invoice.getId())) {
                    totalPaid = totalPaid.plus(crossing.getAppliedAmount());
                }
            }
        }
        invoice.setPaidAmount(totalPaid);
    }

    private String resolveWithholdingAccount(String conceptCode) {
        if (conceptCode == null) return "135595";
        return switch (conceptCode.toUpperCase()) {
            case "RETEFUENTE" -> "135515";
            case "RETEICA" -> "135518";
            case "RETEIVA" -> "240801";
            case "ESTAMPILLA" -> "135595";
            default -> "135595";
        };
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Payment> getPaymentsForInvoice(UUID invoiceId) {
        if (invoiceId == null) {
            return List.of();
        }
        return repositorioPago.findPaymentsByInvoiceId(invoiceId);
    }
}
