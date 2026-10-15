package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.Payment;
import com.sistemaspalacios.billing.domain.model.RetentionApplicationMode;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioVigenciaConcepto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flujo de recaudo por pasarela con retenciones (Sprint 9, HU-51/52/53/54):
 * simulación → checkout PENDIENTE → confirmación de la pasarela → facturas PAGADA.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class GatewayCheckoutWithholdingIntegrationTest {

    private static final String NIT = "900555444-3";

    @Autowired private PaymentService paymentService;
    @Autowired private RepositorioPago repositorioPago;
    @Autowired private RepositorioFactura repositorioFactura;
    @Autowired private RepositorioCliente repositorioCliente;
    @Autowired private RepositorioPeriodo repositorioPeriodo;
    @Autowired private RepositorioVigenciaConcepto repositorioVigenciaConcepto;

    private CorporateClient client;
    private Invoice invoiceA; // 100.000 + 19.000 = 119.000
    private Invoice invoiceB; // 200.000 + 38.000 = 238.000
    private Invoice invoiceC; // 100.000 + 19.000 = 119.000
    private TaxConceptValidity reteFuente;  // 4%, base mínima 150.000
    private TaxConceptValidity reteIca;     // 0,6%
    private TaxConceptValidity reteIva;     // 15%

    @BeforeEach
    void setUp() {
        BillingPeriod period = repositorioPeriodo.findByCode("2026-09").orElseGet(() -> repositorioPeriodo.saveAndFlush(
                new BillingPeriod(UUID.randomUUID(), "2026-09", BillingCutType.ADVANCE, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), true)));
        BillingPeriod otherPeriod = repositorioPeriodo.findByCode("2026-08").orElseGet(() -> repositorioPeriodo.saveAndFlush(
                new BillingPeriod(UUID.randomUUID(), "2026-08", BillingCutType.ADVANCE, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), true)));
        client = repositorioCliente.findByNit(NIT).orElseGet(() -> repositorioCliente.saveAndFlush(new CorporateClient(
                UUID.randomUUID(), NIT, "ALCALDIA DE PRUEBA", "Calle 1", "pagos@prueba.gov.co", "3000000000",
                (short) 1, (short) 1, BillingType.F1, true, "Pasto", null)));

        BillingPeriod thirdPeriod = repositorioPeriodo.findByCode("2026-07").orElseGet(() -> repositorioPeriodo.saveAndFlush(
                new BillingPeriod(UUID.randomUUID(), "2026-07", BillingCutType.ADVANCE, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), true)));

        invoiceA = invoice("RET-A-" + UUID.randomUUID().toString().substring(0, 6), period, 100_000);
        invoiceB = invoice("RET-B-" + UUID.randomUUID().toString().substring(0, 6), otherPeriod, 200_000);
        invoiceC = invoice("RET-C-" + UUID.randomUUID().toString().substring(0, 6), thirdPeriod, 100_000);

        reteFuente = concept(TaxConceptCode.RETEFUENTE, "ReteFuente Servicios 4%", 2, "4.00", 150_000, LocalDate.of(2026, 1, 1), null);
        reteIca = concept(TaxConceptCode.RETEICA, "ReteICA Pasto 0,6%", 1, "0.60", 0, LocalDate.of(2026, 1, 1), null);
        reteIva = concept(TaxConceptCode.RETEIVA, "ReteIVA 15%", 1, "15.00", 0, LocalDate.of(2026, 1, 1), null);
    }

    private Invoice invoice(String number, BillingPeriod period, long subtotal) {
        Invoice invoice = new Invoice(UUID.randomUUID(), client.getId(), period.getId(), number,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 16), BillingType.F1, InvoiceStatus.CONSOLIDATED);
        invoice.setClientNit(NIT);
        invoice.setClientBusinessName(client.getBusinessName());
        invoice.addItem(new InvoiceItem(null, invoice.getId(), BillableItemType.CORPORATE_SERVICE, null, "SERVICIO",
                BigDecimal.ONE, Money.of(subtotal), BigDecimal.ZERO, true, BigDecimal.valueOf(19)));
        invoice.recalculateTotals();
        return repositorioFactura.saveAndFlush(invoice);
    }

    private TaxConceptValidity concept(TaxConceptCode code, String name, int version, String percentage, long minimumBase,
                                       LocalDate from, LocalDate to) {
        return repositorioVigenciaConcepto.saveAndFlush(new TaxConceptValidity(null, name, version, code, null, null,
                Money.of(minimumBase), new BigDecimal(percentage), from, to, true));
    }

    private PaymentService.CheckoutResult checkout(List<Invoice> invoices, TaxConceptValidity... concepts) {
        return paymentService.checkout(new PaymentService.CheckoutCommand(NIT,
                invoices.stream().map(Invoice::getId).toList(),
                java.util.Arrays.stream(concepts).map(GatewayCheckoutWithholdingIntegrationTest::at).toList()));
    }

    private PaymentService.PaymentResult confirm(UUID paymentId, String txId, String status, BigDecimal amount) {
        return paymentService.confirmPayment(new PaymentService.PaymentConfirmationCommand(
                NIT, txId, List.of(), List.of(), amount, LocalDate.now(), "PSE", "PSE - Bancolombia",
                "AUTH-1", txId, null, null, paymentId, status));
    }

    /** El cliente ingresa el porcentaje; en estos tests usa el máximo del catálogo (igual a la tarifa anterior). */
    private static PaymentService.ConceptSelection at(TaxConceptValidity concept) {
        return new PaymentService.ConceptSelection(concept.getId(), concept.getMaxPercentage());
    }

    private InvoiceStatus statusOf(Invoice invoice) {
        return repositorioFactura.findById(invoice.getId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("Caso F: varias facturas se liquidan consolidadas sobre valores oficiales y la simulación no persiste")
    void caseF_multipleInvoicesConsolidated() {
        var sim = paymentService.simulate(new PaymentService.PaymentSimulationCommand(NIT,
                List.of(invoiceA.getId(), invoiceB.getId()), List.of(at(reteFuente), at(reteIva))));

        assertEquals(Money.of(357_000), sim.totalInvoices());
        assertEquals(Money.of(300_000), sim.taxableBase());
        assertEquals(Money.of(57_000), sim.totalIva());
        // La factura A sola (100.000) no alcanza la base mínima; el conjunto (300.000) sí.
        var rf = sim.withholdings().get(0);
        assertTrue(rf.applicable());
        assertEquals(Money.of(300_000), rf.evaluatedBase());
        assertEquals(Money.of(12_000), rf.withheldAmount());
        var iva = sim.withholdings().get(1);
        assertEquals(Money.of(57_000), iva.evaluatedBase());
        assertEquals(Money.of(8_550), iva.withheldAmount());
        assertEquals(Money.of(20_550), sim.totalWithholdings());
        assertEquals(Money.of(336_450), sim.netPayable());

        assertTrue(repositorioPago.findByClientIdOrderByPaymentDateDesc(client.getId()).isEmpty(), "simular no crea pagos");
        assertEquals(InvoiceStatus.CONSOLIDATED, statusOf(invoiceA));
    }

    @Test
    @DisplayName("Checkout: crea el pago PENDIENTE con snapshot completo y no marca facturas PAGADA")
    void checkoutCreatesPendingPaymentWithFullSnapshot() {
        var result = checkout(List.of(invoiceA), reteFuente, reteIca);

        assertEquals(Payment.STATUS_PENDING, result.status());
        assertEquals(Money.of(118_400), result.netPayable());
        assertEquals(InvoiceStatus.CONSOLIDATED, statusOf(invoiceA), "el checkout no paga la factura");

        var detail = paymentService.getPaymentDetail(result.paymentId());
        assertEquals(Money.of(119_000), detail.totalInvoices());
        assertEquals(Money.of(600), detail.totalWithholdings());
        assertEquals(Money.of(118_400), detail.netCollected());
        assertEquals(2, detail.withholdings().size(), "también se conserva el concepto que no aplicó (CA-6, punto 21)");
        var rf = detail.withholdings().stream().filter(w -> w.type().equals("RETEFUENTE")).findFirst().orElseThrow();
        assertFalse(rf.applicable());
        assertEquals(Money.ZERO, rf.withheldAmount());
        assertEquals("No alcanza la base mínima de $150.000", rf.nonApplicableReason());
        assertEquals(2, rf.version());
        assertEquals(LocalDate.of(2026, 1, 1), rf.validFrom());
    }

    @Test
    @DisplayName("CA-9 / idempotencia: confirmación aprobada deja la factura PAGADA aunque el neto sea menor al total")
    void approvedConfirmationPaysInvoicesAndIsIdempotent() {
        var checkout = checkout(List.of(invoiceA), reteIca);
        var first = confirm(checkout.paymentId(), "CUS-1001", "APROBADA", new BigDecimal("118400.00"));

        assertEquals(Payment.STATUS_APPLIED, first.status());
        assertEquals(List.of(invoiceA.getId()), first.paidInvoiceIds());
        Invoice paid = repositorioFactura.findById(invoiceA.getId()).orElseThrow();
        assertEquals(InvoiceStatus.PAID, paid.getStatus());
        assertEquals(Money.of(119_000), paid.getTotalAmount(), "CA-7: el valor oficial no cambia");
        assertEquals(Money.of(100_000), paid.getSubtotalAmount());
        assertEquals(Money.of(19_000), paid.getIvaAmount());

        var detail = paymentService.getPaymentDetail(checkout.paymentId());
        assertEquals(Money.of(119_000), detail.invoices().getFirst().appliedAmount(), "recaudo + retenciones cubren el total");
        assertEquals("CUS-1001", detail.transactionNumber());
        assertNotNull(detail.confirmedAt());

        var again = confirm(checkout.paymentId(), "CUS-1001", "APPROVED", new BigDecimal("118400.00"));
        assertEquals(first.paymentId(), again.paymentId());
        assertEquals(1, repositorioPago.findAppliedPaymentsByInvoiceId(invoiceA.getId()).size(), "no se duplica el pago");
        assertEquals(1, paymentService.getPaymentDetail(checkout.paymentId()).withholdings().size(), "no se duplican retenciones");
        assertThrows(PaymentConflictException.class, () -> confirm(checkout.paymentId(), "CUS-OTRA", "APROBADA", null));
    }

    @Test
    @DisplayName("Caso I: un monto distinto al neto calculado por el servidor no paga la factura")
    void caseI_manipulatedAmountIsRejected() {
        var checkout = checkout(List.of(invoiceA), reteIca);
        assertEquals(Money.of(118_400), checkout.netPayable());

        assertThrows(PaymentConflictException.class, () -> confirm(checkout.paymentId(), "CUS-1", "APROBADA", BigDecimal.ONE));
        assertEquals(InvoiceStatus.CONSOLIDATED, statusOf(invoiceA));
        assertEquals(Payment.STATUS_PENDING, paymentService.getPaymentDetail(checkout.paymentId()).status());

        confirm(checkout.paymentId(), "CUS-1", "APROBADA", new BigDecimal("118400"));
        assertEquals(InvoiceStatus.PAID, statusOf(invoiceA));
    }

    @Test
    @DisplayName("Pasarela rechaza: el pago queda RECHAZADO y la factura sigue pendiente")
    void rejectedTransactionLeavesInvoicePending() {
        var checkout = checkout(List.of(invoiceA));
        var result = confirm(checkout.paymentId(), "CUS-R", "DECLINED", null);
        assertEquals(Payment.STATUS_REJECTED, result.status());
        assertTrue(result.paidInvoiceIds().isEmpty());
        assertEquals(InvoiceStatus.CONSOLIDATED, statusOf(invoiceA));
        assertNotNull(checkout(List.of(invoiceA)).paymentId(), "se puede reintentar");
    }

    @Test
    @DisplayName("Estado del proveedor no reconocido: no se asume aprobado")
    void unknownProviderStatusIsRejected() {
        var checkout = checkout(List.of(invoiceA));
        assertThrows(IllegalArgumentException.class, () -> confirm(checkout.paymentId(), "CUS-X", "QUIZAS", null));
        assertThrows(IllegalArgumentException.class, () -> confirm(checkout.paymentId(), "CUS-X", null, null));
        assertEquals(InvoiceStatus.CONSOLIDATED, statusOf(invoiceA));
    }

    @Test
    @DisplayName("Caso H / CA-10: cambiar el catálogo después no altera el pago histórico")
    void caseH_historicalSnapshotIsImmutable() {
        var checkout = checkout(List.of(invoiceB), reteFuente);
        confirm(checkout.paymentId(), "CUS-H", "APROBADA", checkout.netPayable().amount());

        reteFuente.closeValidity(LocalDate.now().minusDays(1));
        reteFuente.setName("ReteFuente renombrada");
        repositorioVigenciaConcepto.saveAndFlush(reteFuente);
        concept(TaxConceptCode.RETEFUENTE, "ReteFuente Servicios 3,5%", 3, "3.50", 300_000, LocalDate.now(), null);

        var rf = paymentService.getPaymentDetail(checkout.paymentId()).withholdings().getFirst();
        assertEquals("ReteFuente Servicios 4%", rf.name());
        assertEquals(0, rf.percentage().compareTo(new BigDecimal("4")));
        assertEquals(2, rf.version());
        assertEquals(Money.of(150_000), rf.minimumBase());
        assertEquals(Money.of(8_000), rf.withheldAmount());
    }

    @Test
    @DisplayName("Concurrencia: dos pestañas no pueden pagar dos veces la misma factura")
    void concurrentCheckoutsCannotPayTwice() {
        var tab1 = checkout(List.of(invoiceA));
        assertEquals(tab1.paymentId(), checkout(List.of(invoiceA)).paymentId(), "misma selección reutiliza el checkout");

        var tab2 = checkout(List.of(invoiceA), reteIca);
        assertNotEquals(tab1.paymentId(), tab2.paymentId());
        assertEquals(Payment.STATUS_CANCELLED, paymentService.getPaymentDetail(tab1.paymentId()).status());

        assertThrows(PaymentConflictException.class, () -> confirm(tab1.paymentId(), "CUS-T1", "APROBADA", null));
        confirm(tab2.paymentId(), "CUS-T2", "APROBADA", tab2.netPayable().amount());
        assertEquals(InvoiceStatus.PAID, statusOf(invoiceA));

        assertThrows(PaymentConflictException.class, () -> checkout(List.of(invoiceA)), "una factura PAGADA no admite nuevo checkout");
    }

    @Test
    @DisplayName("Registro manual del analista anula el checkout abierto de la pasarela")
    void manualRegistrationCancelsOpenCheckout() {
        var open = checkout(List.of(invoiceA));
        paymentService.register(new PaymentService.PaymentRegistrationCommand(NIT, List.of(invoiceA.getId()), List.of(),
                LocalDate.now(), "TRANSFERENCIA", "11100501", "MAN-" + UUID.randomUUID(), null));
        assertEquals(Payment.STATUS_CANCELLED, paymentService.getPaymentDetail(open.paymentId()).status());
        assertThrows(PaymentConflictException.class, () -> confirm(open.paymentId(), "CUS-LATE", "APROBADA", null));
    }

    @Test
    @DisplayName("Seguridad: conceptos inexistentes, vencidos o duplicados por tipo y facturas ajenas o repetidas se rechazan")
    void invalidSelectionsAreRejected() {
        UUID fake = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(invoiceA.getId()), List.of(new PaymentService.ConceptSelection(fake, BigDecimal.ONE)))));

        var expired = concept(TaxConceptCode.RETEICA, "ReteICA vencida", 1, "1.00", 0, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(invoiceA.getId()), List.of(at(expired)))));

        var otherRf = concept(TaxConceptCode.RETEFUENTE, "ReteFuente Software 3,5%", 1, "3.50", 0, LocalDate.of(2026, 1, 1), null);
        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(invoiceA.getId()), List.of(at(reteFuente), at(otherRf)))));

        var estampilla1 = concept(TaxConceptCode.ESTAMPILLA, "Estampilla Procultura 1%", 1, "1.00", 0, LocalDate.of(2026, 1, 1), null);
        var estampilla2 = concept(TaxConceptCode.ESTAMPILLA, "Estampilla Prodeporte 0,5%", 1, "0.50", 0, LocalDate.of(2026, 1, 1), null);
        var sim = paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(invoiceA.getId()), List.of(at(estampilla1), at(estampilla2))));
        assertEquals(Money.of(1_500), sim.totalWithholdings(), "varias estampillas sí pueden coexistir");

        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(invoiceA.getId(), invoiceA.getId(), invoiceB.getId()), List.of())), "no se suma A dos veces");

        CorporateClient foreignClient = repositorioCliente.saveAndFlush(new CorporateClient(
                UUID.randomUUID(), "FOREIGN-" + UUID.randomUUID().toString().substring(0, 8), "Otro cliente de prueba",
                null, null, null, (short) 1, (short) 1, BillingType.F1, false, null, null));
        Invoice foreign = new Invoice(UUID.randomUUID(), foreignClient.getId(), invoiceA.getPeriodId(), BillingType.F1,
                foreignClient, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        foreign.setInvoiceNumber("FOREIGN-1");
        foreign.addItem(InvoiceItem.fromOfficialAmounts(foreign.getId(), "INTERNET", "Servicio de otro cliente",
                BigDecimal.ONE, Money.of(100), Money.of(19)));
        foreign.consolidate();
        repositorioFactura.saveAndFlush(foreign);
        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(foreign.getId()), List.of())));
    }

    // --- Modo de aplicación de retenciones (CA-11 a CA-17) ---

    private static PaymentService.InvoiceRetentionSelection select(Invoice invoice, TaxConceptValidity... concepts) {
        return new PaymentService.InvoiceRetentionSelection(invoice.getId(),
                java.util.Arrays.stream(concepts).map(GatewayCheckoutWithholdingIntegrationTest::at).toList());
    }

    private PaymentService.PaymentSimulationResult simulatePerInvoice(PaymentService.InvoiceRetentionSelection... selections) {
        return paymentService.simulate(PaymentService.PaymentSimulationCommand.perInvoice(NIT, List.of(selections)));
    }

    @Test
    @DisplayName("Test obligatorio: con la misma selección, PER_INVOICE y CONSOLIDATED dan resultados distintos")
    void modesProduceDifferentResultsByDesign() {
        // Dos facturas de 100.000 de base; ReteFuente 4% con base mínima 150.000.
        var perInvoice = simulatePerInvoice(select(invoiceA, reteFuente), select(invoiceC, reteFuente));
        assertEquals(RetentionApplicationMode.PER_INVOICE, perInvoice.applicationMode());
        for (var settled : perInvoice.invoices()) {
            var rf = settled.withholdings().getFirst();
            assertEquals(Money.of(100_000), rf.evaluatedBase(), "cada factura se evalúa con su propia base");
            assertFalse(rf.applicable(), "100.000 < 150.000: no aplica por factura");
            assertEquals(Money.ZERO, settled.totalWithholdings());
            assertEquals(Money.of(119_000), settled.netAmount());
        }
        assertEquals(Money.ZERO, perInvoice.totalWithholdings());
        assertEquals(Money.of(238_000), perInvoice.netPayable());

        var consolidated = paymentService.simulate(new PaymentService.PaymentSimulationCommand(NIT,
                List.of(invoiceA.getId(), invoiceC.getId()), List.of(at(reteFuente))));
        assertEquals(RetentionApplicationMode.CONSOLIDATED, consolidated.applicationMode());
        var rf = consolidated.withholdings().getFirst();
        assertEquals(Money.of(200_000), rf.evaluatedBase(), "las bases se consolidan antes de validar la base mínima");
        assertTrue(rf.applicable());
        assertEquals(Money.of(8_000), consolidated.totalWithholdings());
        assertEquals(Money.of(230_000), consolidated.netPayable());
        assertNull(rf.invoiceId(), "la retención consolidada no pertenece a ninguna factura");
        assertTrue(consolidated.invoices().stream().allMatch(i -> i.withholdings().isEmpty() && i.netAmount() == null),
                "una retención consolidada no se reparte entre facturas");

        assertNotEquals(perInvoice.totalWithholdings(), consolidated.totalWithholdings(),
                "comportamiento esperado: el modo cambia el resultado y por eso lo elige el cliente");
    }

    @Test
    @DisplayName("Invariancia: con una sola factura y los mismos conceptos ambos modos coinciden")
    void singleInvoiceIsModeInvariant() {
        var perInvoice = simulatePerInvoice(select(invoiceB, reteFuente, reteIca, reteIva));
        var consolidated = paymentService.simulate(new PaymentService.PaymentSimulationCommand(NIT,
                List.of(invoiceB.getId()), List.of(at(reteFuente), at(reteIca), at(reteIva))));

        assertEquals(consolidated.totalInvoices(), perInvoice.totalInvoices());
        assertEquals(consolidated.totalWithholdings(), perInvoice.totalWithholdings());
        assertEquals(consolidated.netBeforeRounding(), perInvoice.netBeforeRounding());
        assertEquals(consolidated.roundingAdjustment(), perInvoice.roundingAdjustment());
        assertEquals(consolidated.netPayable(), perInvoice.netPayable());
        assertEquals(Money.of(223_100), perInvoice.netPayable(), "238.000 - 8.000 - 1.200 - 5.700");
        for (int i = 0; i < 3; i++) {
            var c = consolidated.withholdings().get(i);
            var p = perInvoice.invoices().getFirst().withholdings().get(i);
            assertEquals(c.evaluatedBase(), p.evaluatedBase());
            assertEquals(c.withheldAmount(), p.withheldAmount());
            assertEquals(c.applicable(), p.applicable());
        }
    }

    @Test
    @DisplayName("CA-12 / CA-14: PER_INVOICE evalúa conceptos distintos por factura y ReteIVA sobre el IVA de cada una")
    void perInvoiceUsesEachInvoiceOwnBaseAndIva() {
        var result = simulatePerInvoice(select(invoiceA, reteFuente), select(invoiceB, reteIca, reteIva));

        var a = result.invoices().stream().filter(i -> i.invoiceId().equals(invoiceA.getId())).findFirst().orElseThrow();
        assertFalse(a.withholdings().getFirst().applicable());
        assertEquals(Money.of(119_000), a.netAmount());

        var b = result.invoices().stream().filter(i -> i.invoiceId().equals(invoiceB.getId())).findFirst().orElseThrow();
        var iva = b.withholdings().stream().filter(w -> w.type().equals("RETEIVA")).findFirst().orElseThrow();
        assertEquals(Money.of(38_000), iva.evaluatedBase(), "ReteIVA sobre el IVA de esa factura, no el consolidado (57.000)");
        assertEquals(Money.of(5_700), iva.withheldAmount());
        assertEquals(Money.of(6_900), b.totalWithholdings(), "1.200 ReteICA + 5.700 ReteIVA");
        assertEquals(Money.of(231_100), b.netAmount());

        assertEquals(Money.of(357_000), result.totalInvoices());
        assertEquals(Money.of(6_900), result.totalWithholdings());
        assertEquals(Money.of(350_100), result.netPayable());
    }

    @Test
    @DisplayName("CA-15 / auditoría: el pago conserva el modo y cada retención identifica su factura")
    void paymentKeepsModeAndPerInvoiceSnapshots() {
        var checkout = paymentService.checkout(PaymentService.CheckoutCommand.perInvoice(NIT,
                List.of(select(invoiceA, reteFuente), select(invoiceB, reteIca, reteIva))));
        assertEquals(Money.of(350_100), checkout.netPayable());
        confirm(checkout.paymentId(), "CUS-MODE", "APROBADA", new BigDecimal("350100"));

        var detail = paymentService.getPaymentDetail(checkout.paymentId());
        assertEquals(RetentionApplicationMode.PER_INVOICE, detail.applicationMode());
        assertEquals(3, detail.withholdings().size(), "seleccionados: 1 en A y 2 en B, incluido el que no aplicó");
        var rfA = detail.withholdings().stream().filter(w -> w.type().equals("RETEFUENTE")).findFirst().orElseThrow();
        assertEquals(invoiceA.getId(), rfA.invoiceId());
        assertFalse(rfA.applicable());
        assertTrue(detail.withholdings().stream().filter(w -> !w.type().equals("RETEFUENTE"))
                .allMatch(w -> invoiceB.getId().equals(w.invoiceId())));
        assertEquals(InvoiceStatus.PAID, statusOf(invoiceA));
        assertEquals(InvoiceStatus.PAID, statusOf(invoiceB));
        assertEquals(Money.of(357_000), detail.invoices().stream().map(PaymentService.PaymentInvoiceLine::appliedAmount)
                .reduce(Money.ZERO, Money::plus), "obligaciones = recaudo + retenciones aceptadas");

        var consolidated = checkout(List.of(invoiceC), reteIca);
        var consolidatedDetail = paymentService.getPaymentDetail(consolidated.paymentId());
        assertEquals(RetentionApplicationMode.CONSOLIDATED, consolidatedDetail.applicationMode());
        assertNull(consolidatedDetail.withholdings().getFirst().invoiceId());
    }

    @Test
    @DisplayName("CA-17: no se aceptan payloads sin modo ni que mezclen retenciones consolidadas y por factura")
    void ambiguousPayloadsAreRejected() {
        var perInvoiceSelection = List.of(select(invoiceA, reteIca));
        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(invoiceA.getId()), List.of(), List.of(), null, null, List.of())), "sin modo");
        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(invoiceA.getId(), invoiceB.getId()), List.of(at(reteFuente)), List.of(), null,
                RetentionApplicationMode.CONSOLIDATED, perInvoiceSelection)), "ambas formas a la vez");
        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(), List.of(), List.of(), null, RetentionApplicationMode.CONSOLIDATED, perInvoiceSelection)),
                "CONSOLIDATED con selección por factura");
        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(invoiceA.getId()), List.of(), List.of(), null, RetentionApplicationMode.PER_INVOICE, List.of())),
                "PER_INVOICE con invoiceIds");
        assertThrows(IllegalArgumentException.class, () -> simulatePerInvoice(select(invoiceA, reteIca), select(invoiceA)),
                "factura repetida");
        assertThrows(IllegalArgumentException.class, () -> paymentService.checkout(new PaymentService.CheckoutCommand(
                NIT, List.of(invoiceA.getId()), List.of(at(reteIca)), RetentionApplicationMode.PER_INVOICE, perInvoiceSelection)));
    }

    // --- Porcentaje ingresado por el cliente ---

    @Test
    @DisplayName("El porcentaje lo ingresa el cliente: se aplica el suyo y el pago guarda aplicado y máximo")
    void clientPercentageIsAppliedAndSnapshotted() {
        var selection = new PaymentService.ConceptSelection(reteFuente.getId(), new BigDecimal("3.5"));
        var checkout = paymentService.checkout(new PaymentService.CheckoutCommand(NIT, List.of(invoiceB.getId()), List.of(selection)));

        var rf = checkout.settlement().withholdings().getFirst();
        assertEquals(0, rf.percentage().compareTo(new BigDecimal("3.5")));
        assertEquals(Money.of(7_000), rf.withheldAmount(), "200.000 × 3,5% (no el 4% máximo del catálogo)");
        assertEquals(Money.of(231_000), checkout.netPayable());

        var snapshot = paymentService.getPaymentDetail(checkout.paymentId()).withholdings().getFirst();
        assertEquals(0, snapshot.percentage().compareTo(new BigDecimal("3.5")), "porcentaje pagado");
        assertEquals(0, snapshot.maxPercentage().compareTo(new BigDecimal("4")), "máximo permitido al liquidar");
        assertEquals(Money.of(7_000), snapshot.withheldAmount());
    }

    @Test
    @DisplayName("El porcentaje del cliente es obligatorio, mayor que 0, hasta 4 decimales y no supera el máximo")
    void clientPercentageIsValidatedAgainstMaximum() {
        for (String invalid : new String[]{"4.01", "0", "-1", "3.12345"}) {
            var selection = new PaymentService.ConceptSelection(reteFuente.getId(), new BigDecimal(invalid));
            assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                    NIT, List.of(invoiceB.getId()), List.of(selection))), invalid);
        }
        var missing = new PaymentService.ConceptSelection(reteFuente.getId(), null);
        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                NIT, List.of(invoiceB.getId()), List.of(missing))), "sin porcentaje");

        var ica = new PaymentService.ConceptSelection(reteIca.getId(), new BigDecimal("0.5"));
        var perInvoice = simulatePerInvoice(new PaymentService.InvoiceRetentionSelection(invoiceB.getId(), List.of(ica)));
        assertEquals(Money.of(1_000), perInvoice.totalWithholdings(), "200.000 × 0,5% en modo por factura");
    }
}
