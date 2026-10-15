package com.sistemaspalacios.billing.web.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.sistemaspalacios.billing.domain.model.RetentionApplicationMode;
import com.sistemaspalacios.billing.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.PaymentDeduction;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller for Collections and Payments (Sprint 9, HU-51 to HU-55).
 */
@RestController
@RequestMapping({"/api/v1/pagos", "/pagos"})
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;
    private final SelectionAuditResolver auditResolver;

    public PaymentController(PaymentService paymentService, SelectionAuditResolver auditResolver) {
        this.paymentService = Objects.requireNonNull(paymentService, "paymentService cannot be null");
        this.auditResolver = Objects.requireNonNull(auditResolver, "auditResolver cannot be null");
    }

    /**
     * Liquidación informativa (HU-51/52): no persiste nada. El cliente solo envía facturas y
     * conceptos; porcentajes, bases y vigencias se leen del catálogo en el servidor.
     */
    @PostMapping("/simular")
    @PreAuthorize("hasAnyAuthority('PAYMENTS_REGISTER', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<PaymentService.PaymentSimulationResult> simulatePayment(
            @RequestBody SimulatePaymentRequest request, jakarta.servlet.http.HttpServletRequest http
    ) {
        boolean staff = isStaff();
        if (!staff && request.deductions() != null && !request.deductions().isEmpty()) {
            // Los ajustes manuales de retención son una facultad del analista, nunca del pagador.
            throw new org.springframework.security.access.AccessDeniedException("Solo un analista puede ajustar valores de retención");
        }
        PaymentService.PaymentSimulationCommand cmd = new PaymentService.PaymentSimulationCommand(
                request.clientNit(),
                request.invoiceIds(),
                request.concepts(),
                staff ? request.deductions() : List.of(),
                staff ? request.paymentDate() : null,
                request.applicationMode(),
                request.invoices()
        );
        return ResponseEntity.ok(paymentService.simulate(cmd, auditResolver.resolve(http, SelectionAuditResolver.Operation.SIMULATE)));
    }

    /**
     * Inicia el pago en la pasarela: recalcula la liquidación y crea el pago PENDIENTE.
     * Cualquier monto enviado por el cliente (p. ej. netoAPagar) se ignora; las facturas solo
     * pasan a PAGADA cuando la pasarela confirma en /confirmar o /webhook.
     */
    @PostMapping("/checkout")
    @PreAuthorize("hasAnyAuthority('PAYMENTS_REGISTER', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<PaymentService.CheckoutResult> checkout(@RequestBody CheckoutRequest request,
                                                                  jakarta.servlet.http.HttpServletRequest http) {
        PaymentService.CheckoutResult result = paymentService.checkout(new PaymentService.CheckoutCommand(
                request.clientNit(), request.invoiceIds(), request.concepts(),
                request.applicationMode(), request.invoices()), auditResolver.resolve(http, SelectionAuditResolver.Operation.CHECKOUT));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** Registro manual de un recaudo ya confirmado por el analista: aplica el pago de inmediato. */
    @PostMapping
    @PreAuthorize("hasAuthority('PAYMENTS_REGISTER')")
    public ResponseEntity<PaymentService.PaymentResult> registerPayment(
            @RequestBody RegisterPaymentRequest request, jakarta.servlet.http.HttpServletRequest http
    ) {
        PaymentService.PaymentRegistrationCommand cmd = new PaymentService.PaymentRegistrationCommand(
                request.clientNit(),
                request.invoiceIds(),
                request.concepts(),
                request.paymentDate(),
                request.paymentMethod(),
                request.bankAccount(),
                request.transactionNumber(),
                request.observation(), request.idempotencyKey(), request.expectedNetAmount(), request.deductions(),
                request.applicationMode(), request.invoices()
        );
        PaymentService.PaymentResult result = paymentService.register(cmd, auditResolver.resolve(http, SelectionAuditResolver.Operation.REGISTER));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Endpoint para confirmar pagos de una o varias facturas desde la pasarela (SP Pagos).
     * Soporta identificación por invoiceKeys ("F1:1001", etc.) o invoiceIds.
     * Es estrictamente idempotente por transactionId.
     */
    @PostMapping("/confirmar")
    @PreAuthorize("permitAll()")
    public ResponseEntity<PaymentService.PaymentResult> confirmPayment(
            @RequestBody PaymentService.PaymentConfirmationCommand command
    ) {
        return confirm("/confirmar", command);
    }

    /**
     * Webhook de confirmación de pasarelas de pago externas (PSE, Redeban, etc.).
     */
    @PostMapping("/webhook")
    @PreAuthorize("permitAll()")
    public ResponseEntity<PaymentService.PaymentResult> paymentWebhook(
            @RequestBody PaymentService.PaymentConfirmationCommand command
    ) {
        return confirm("/webhook", command);
    }

    // Ambos endpoints terminan en el mismo método de servicio que usa el registro manual;
    // los logs permiten ver en orden si la confirmación llegó, qué respondió y por qué falló.
    private ResponseEntity<PaymentService.PaymentResult> confirm(String source, PaymentService.PaymentConfirmationCommand command) {
        log.info("=== CONFIRMACION DE PAGO RECIBIDA ({}) === payload: {}", source, command);
        try {
            PaymentService.PaymentResult result = paymentService.confirmPayment(command);
            log.info("=== RESPUESTA {} HTTP 200 === paymentId={} transactionId={} facturas={}",
                    source, result.paymentId(), result.transactionNumber(), result.paidInvoiceIds());
            return ResponseEntity.ok(result);
        } catch (RuntimeException ex) {
            log.warn("=== CONFIRMACION DE PAGO FALLIDA ({}) === transactionId={} error={}: {}",
                    source, command != null ? command.transactionId() : null, ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        }
    }

    /** Detalle del pago con el snapshot de retenciones con el que se liquidó (HU-54). */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PAYMENTS_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<PaymentService.PaymentDetail> getPayment(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.getPaymentDetail(id));
    }

    @GetMapping("/factura/{invoiceId}")
    @PreAuthorize("hasAuthority('PAYMENTS_VIEW')")
    public ResponseEntity<List<PaymentService.PaymentDetail>> getPaymentsForInvoice(@PathVariable UUID invoiceId) {
        return ResponseEntity.ok(paymentService.getPaymentDetailsForInvoice(invoiceId));
    }

    @GetMapping(value = "/{id}/recibo", produces = MediaType.TEXT_HTML_VALUE)
    @PreAuthorize("hasAnyAuthority('PAYMENTS_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<String> getReceiptHtml(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.getReceiptHtml(id));
    }

    @GetMapping(value = "/{id}/interfaz-siigo", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("hasAuthority('PAYMENTS_VIEW')")
    public ResponseEntity<byte[]> exportPaymentFlatFile(@PathVariable UUID id) {
        byte[] fileBytes = paymentService.exportPaymentFlatFile(id);
        String filename = "pago_siigo_" + id.toString().substring(0, 8) + ".txt";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(fileBytes);
    }

    @GetMapping("/cartera/{nit}")
    @PreAuthorize("hasAnyAuthority('CARTERA_VIEW', 'PAYMENTS_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<PaymentService.ClientPortfolioSummary> getClientPortfolio(@PathVariable String nit) {
        return ResponseEntity.ok(paymentService.getClientPortfolio(nit));
    }

    /** Un cliente desactualizado que envía solo ids no puede quedar liquidado sin retenciones en silencio. */
    private static List<PaymentService.ConceptSelection> requirePercentages(List<UUID> legacyIds,
                                                                           List<PaymentService.ConceptSelection> selections) {
        if (legacyIds != null && !legacyIds.isEmpty()) {
            throw new IllegalArgumentException("Envíe selectedConcepts con el porcentaje de cada retención (conceptId, percentage)");
        }
        return selections;
    }

    /** Personal interno (no la pasarela): quien tiene el permiso de registrar pagos. */
    private static boolean isStaff() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().map(a -> a.getAuthority())
                .anyMatch("PAYMENTS_REGISTER"::equals);
    }

    public record SimulatePaymentRequest(
            @JsonAlias("nit") String clientNit,
            List<UUID> invoiceIds,
            List<PaymentService.ConceptSelection> selectedConcepts,
            // Formato anterior (solo ids, sin porcentaje): se rechaza en vez de ignorarse.
            List<UUID> selectedConceptIds,
            List<PaymentDeduction> deductions,
            LocalDate paymentDate,
            // Obligatorio: el modo nunca se infiere (CONSOLIDATED usa invoiceIds + selectedConceptIds;
            // PER_INVOICE usa invoices[]). Mezclar ambas formas se rechaza.
            RetentionApplicationMode applicationMode,
            List<PaymentService.InvoiceRetentionSelection> invoices
    ) {
        public SimulatePaymentRequest(String nit, List<UUID> ids, List<PaymentService.ConceptSelection> concepts) {
            this(nit, ids, concepts, null, List.of(), null, RetentionApplicationMode.CONSOLIDATED, null);
        }

        List<PaymentService.ConceptSelection> concepts() {
            return requirePercentages(selectedConceptIds, selectedConcepts);
        }
    }

    /** Solo facturas, modo y conceptos: no existe campo de monto que el cliente pueda manipular. */
    public record CheckoutRequest(
            @JsonAlias("nit") String clientNit,
            List<UUID> invoiceIds,
            List<PaymentService.ConceptSelection> selectedConcepts,
            List<UUID> selectedConceptIds,
            RetentionApplicationMode applicationMode,
            List<PaymentService.InvoiceRetentionSelection> invoices
    ) {
        List<PaymentService.ConceptSelection> concepts() {
            return requirePercentages(selectedConceptIds, selectedConcepts);
        }
    }

    public record RegisterPaymentRequest(
            String clientNit,
            List<UUID> invoiceIds,
            List<PaymentService.ConceptSelection> selectedConcepts,
            LocalDate paymentDate,
            String paymentMethod,
            String bankAccount,
            String transactionNumber,
            String observation,
            String idempotencyKey,
            Money expectedNetAmount,
            List<PaymentDeduction> deductions,
            RetentionApplicationMode applicationMode,
            List<PaymentService.InvoiceRetentionSelection> invoices
    ) {
        List<PaymentService.ConceptSelection> concepts() {
            return selectedConcepts;
        }

        public RegisterPaymentRequest(String nit, List<UUID> ids, List<PaymentService.ConceptSelection> concepts, LocalDate date,
                String method, String bank, String transaction, String observation) {
            this(nit, ids, concepts, date, method, bank, transaction, observation, null, null, List.of(),
                    RetentionApplicationMode.CONSOLIDATED, null);
        }
    }
}
