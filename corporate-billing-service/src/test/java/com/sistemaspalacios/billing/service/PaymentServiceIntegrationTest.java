package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.Payment;
import com.sistemaspalacios.billing.domain.model.PaymentWithholding;
import com.sistemaspalacios.billing.domain.model.ServiceClassification;
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

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class PaymentServiceIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RepositorioPago repositorioPago;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioVigenciaConcepto repositorioVigenciaConcepto;

    private CorporateClient testClient;
    private BillingPeriod periodAug;
    private BillingPeriod periodSep;
    private Invoice invoiceAug;
    private Invoice invoiceSep;
    private TaxConceptValidity rfConcept;
    private TaxConceptValidity reteIvaConcept;

    @BeforeEach
    void setUp() {
        periodAug = repositorioPeriodo.findByCode("2026-08").orElseGet(() -> {
            BillingPeriod p = new BillingPeriod(UUID.randomUUID(), "2026-08", BillingCutType.ADVANCE, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), true);
            return repositorioPeriodo.saveAndFlush(p);
        });

        periodSep = repositorioPeriodo.findByCode("2026-09").orElseGet(() -> {
            BillingPeriod p = new BillingPeriod(UUID.randomUUID(), "2026-09", BillingCutType.ADVANCE, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), true);
            return repositorioPeriodo.saveAndFlush(p);
        });

        testClient = repositorioCliente.findByNit("900888777-1").orElseGet(() -> {
            CorporateClient c = new CorporateClient(
                    UUID.randomUUID(),
                    "900888777-1",
                    "GOBERNACION DE NARIÑO",
                    "Calle 19 # 23-00",
                    "hacienda@narino.gov.co",
                    "3150001122",
                    (short) 1,
                    (short) 1,
                    BillingType.F1,
                    true,
                    "Pasto",
                    null
            );
            return repositorioCliente.saveAndFlush(c);
        });

        // Factura 1: Agosto ($2,000,000 subtotal + $380,000 IVA = $2,380,000 total)
        invoiceAug = new Invoice(
                UUID.randomUUID(),
                testClient.getId(),
                periodAug.getId(),
                "FESP-TEST-001",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 16),
                BillingType.F1,
                InvoiceStatus.CONSOLIDATED
        );
        invoiceAug.setClientNit(testClient.getNit());
        invoiceAug.setClientBusinessName(testClient.getBusinessName());
        invoiceAug.addItem(new InvoiceItem(
                null,
                invoiceAug.getId(),
                com.sistemaspalacios.billing.domain.model.BillableItemType.CORPORATE_SERVICE,
                null,
                "SERVICIO AGOSTO",
                BigDecimal.ONE,
                Money.of(2_000_000),
                BigDecimal.ZERO,
                true,
                BigDecimal.valueOf(19)
        ));
        invoiceAug.recalculateTotals();
        invoiceAug = repositorioFactura.saveAndFlush(invoiceAug);

        // Factura 2: Septiembre ($1,000,000 subtotal + $190,000 IVA = $1,190,000 total)
        invoiceSep = new Invoice(
                UUID.randomUUID(),
                testClient.getId(),
                periodSep.getId(),
                "FESP-TEST-002",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 16),
                BillingType.F1,
                InvoiceStatus.CONSOLIDATED
        );
        invoiceSep.setClientNit(testClient.getNit());
        invoiceSep.setClientBusinessName(testClient.getBusinessName());
        invoiceSep.addItem(new InvoiceItem(
                null,
                invoiceSep.getId(),
                com.sistemaspalacios.billing.domain.model.BillableItemType.CORPORATE_SERVICE,
                null,
                "SERVICIO SEPTIEMBRE",
                BigDecimal.ONE,
                Money.of(1_000_000),
                BigDecimal.ZERO,
                true,
                BigDecimal.valueOf(19)
        ));
        invoiceSep.recalculateTotals();
        invoiceSep = repositorioFactura.saveAndFlush(invoiceSep);

        // Conceptos tributarios en catálogo
        rfConcept = new TaxConceptValidity(
                null,
                "ReteFuente 4% Oficial",
                1,
                TaxConceptCode.RETEFUENTE,
                ServiceClassification.INTERNET,
                null,
                Money.of(500_000),
                BigDecimal.valueOf(4.00),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );
        rfConcept = repositorioVigenciaConcepto.saveAndFlush(rfConcept);

        reteIvaConcept = new TaxConceptValidity(
                null,
                "ReteIVA 15% Oficial",
                1,
                TaxConceptCode.RETEIVA,
                ServiceClassification.INTERNET,
                null,
                Money.ZERO,
                BigDecimal.valueOf(15.00),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );
        reteIvaConcept = repositorioVigenciaConcepto.saveAndFlush(reteIvaConcept);
    }

    @Test
    @DisplayName("HU-51: Simulación de pago con retenciones y sin persistir")
    void testSimulatePayment() {
        PaymentService.PaymentSimulationCommand cmd = new PaymentService.PaymentSimulationCommand(
                testClient.getNit(),
                List.of(invoiceAug.getId()),
                List.of(at(rfConcept), at(reteIvaConcept))
        );

        PaymentService.PaymentSimulationResult sim = paymentService.simulate(cmd);

        assertNotNull(sim);
        assertEquals(Money.of(2_380_000), sim.totalInvoices());
        assertEquals(Money.of(2_000_000), sim.taxableBase());
        assertEquals(Money.of(380_000), sim.totalIva());

        // ReteFuente: 2,000,000 * 4% = 80,000
        // ReteIVA: 380,000 * 15% = 57,000
        // Total withholdings = 137,000
        assertEquals(Money.of(137_000), sim.totalWithholdings());

        // Neto = 2,380,000 - 137,000 = 2,243,000
        assertEquals(Money.of(2_243_000), sim.netBeforeRounding());
        assertEquals(Money.of(2_243_000), sim.netPayable());

        // BD no debe tener ningún pago creado
        assertTrue(repositorioPago.findByClientIdOrderByPaymentDateDesc(testClient.getId()).isEmpty());
    }

    @Test
    @DisplayName("HU-53, HU-54: Pago parcial, factura pagada cambia a PAGADA, aviso de factura pendiente y versiones inmutables")
    void testRegisterPartialPaymentAndVersionImmutability() {
        // Paga solo la factura de Agosto, dejando Septiembre pendiente
        PaymentService.PaymentRegistrationCommand cmd = new PaymentService.PaymentRegistrationCommand(
                testClient.getNit(),
                List.of(invoiceAug.getId()),
                List.of(at(rfConcept), at(reteIvaConcept)),
                LocalDate.of(2026, 8, 25),
                "TRANSFERENCIA_BANCARIA",
                "11100501", // Bancolombia
                "TRX-998877",
                "Pago mensualidad agosto 2026"
        );

        PaymentService.PaymentResult result = paymentService.register(cmd);

        assertNotNull(result);
        assertNotNull(result.paymentId());
        assertTrue(result.partialPayment(), "Debe ser marcado como pago parcial porque Septiembre sigue pendiente");
        assertNotNull(result.partialPaymentNotice());
        assertTrue(result.partialPaymentNotice().contains("continúa pendiente"));
        assertEquals(1, result.remainingPendingInvoicesCount());
        assertEquals(Money.of(1_190_000), result.remainingPendingDebt());

        // Verificar estado de la factura de Agosto
        Invoice updatedAug = repositorioFactura.findById(invoiceAug.getId()).orElseThrow();
        assertEquals(InvoiceStatus.PAID, updatedAug.getStatus());
        assertEquals(Money.of(2_380_000), updatedAug.getPaidAmount());
        assertEquals(Money.ZERO, updatedAug.getPendingBalance());

        // Verificar estado de la factura de Septiembre
        Invoice updatedSep = repositorioFactura.findById(invoiceSep.getId()).orElseThrow();
        assertEquals(InvoiceStatus.CONSOLIDATED, updatedSep.getStatus());
        assertEquals(Money.of(1_190_000), updatedSep.getPendingBalance());

        // HU-54: Verificar inmutabilidad de versiones históricas en retencion_pago
        Payment payment = repositorioPago.findById(result.paymentId()).orElseThrow();
        assertEquals(2, payment.getWithholdings().size());

        PaymentWithholding rfWithholding = payment.getWithholdings().stream()
                .filter(w -> "RETEFUENTE".equals(w.getConceptCode()))
                .findFirst().orElseThrow();
        assertEquals(1, rfWithholding.getVersionNumber());
        assertEquals(BigDecimal.valueOf(4.00), rfWithholding.getPercentage());
        assertEquals(Money.of(500_000), rfWithholding.getMinimumBase());
        assertEquals(Money.of(2_000_000), rfWithholding.getCalculationBase());
        assertEquals(Money.of(80_000), rfWithholding.getWithheldAmount());

        // Ahora alteramos el catálogo simulando una nueva versión legal
        rfConcept.closeValidity(LocalDate.of(2026, 8, 31));
        repositorioVigenciaConcepto.saveAndFlush(rfConcept);

        TaxConceptValidity rfVersion2 = new TaxConceptValidity(
                null,
                "ReteFuente 3.5% Nueva Reforma",
                2,
                TaxConceptCode.RETEFUENTE,
                ServiceClassification.INTERNET,
                null,
                Money.of(1_500_000),
                BigDecimal.valueOf(3.50),
                LocalDate.of(2026, 9, 1),
                null,
                true
        );
        repositorioVigenciaConcepto.saveAndFlush(rfVersion2);

        // Consultar nuevamente el pago: ¡El histórico debe permanecer exactamente igual!
        Payment reloadedPayment = repositorioPago.findById(result.paymentId()).orElseThrow();
        PaymentWithholding reloadedRf = reloadedPayment.getWithholdings().stream()
                .filter(w -> "RETEFUENTE".equals(w.getConceptCode()))
                .findFirst().orElseThrow();
        assertEquals(1, reloadedRf.getVersionNumber());
        assertEquals(BigDecimal.valueOf(4.00), reloadedRf.getPercentage());
        assertEquals(Money.of(80_000), reloadedRf.getWithheldAmount());
    }

    @Test
    @DisplayName("HU-55: Generación de Recibo de Caja en HTML e Interfaz de Pago para Siigo")
    void testReceiptAndSiigoFlatFile() {
        PaymentService.PaymentRegistrationCommand cmd = new PaymentService.PaymentRegistrationCommand(
                testClient.getNit(),
                List.of(invoiceAug.getId()),
                List.of(at(rfConcept)),
                LocalDate.of(2026, 8, 20),
                "CONSIGNACION",
                "11100501",
                "CONS-12345",
                "Pago total"
        );

        PaymentService.PaymentResult result = paymentService.register(cmd);

        // 1. Recibo de caja HTML
        String htmlReceipt = paymentService.getReceiptHtml(result.paymentId());
        assertNotNull(htmlReceipt);
        assertTrue(htmlReceipt.contains("RECIBO DE CAJA"));
        assertTrue(htmlReceipt.contains("GOBERNACION DE NARIÑO"));
        assertTrue(htmlReceipt.contains("900888777-1"));
        assertTrue(htmlReceipt.contains("CONS-12345"));

        // 2. Interfaz plana para Siigo (RC)
        byte[] flatFile = paymentService.exportPaymentFlatFile(result.paymentId());
        assertNotNull(flatFile);
        assertTrue(flatFile.length > 0);
        String flatText = new String(flatFile, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(flatText.contains("RC"));
        assertTrue(flatText.contains("11100501")); // Cuenta de banco
    }

    @Test
    @DisplayName("Consulta de estado de cartera de cliente")
    void testGetClientPortfolio() {
        PaymentService.ClientPortfolioSummary portfolio = paymentService.getClientPortfolio(testClient.getNit());

        assertNotNull(portfolio);
        assertEquals(testClient.getNit(), portfolio.clientNit());
        assertFalse(portfolio.upToDate());
        assertEquals(2, portfolio.totalPendingInvoices());
        // 2,380,000 + 1,190,000 = 3,570,000
        assertEquals(Money.of(3_570_000), portfolio.totalOutstandingDebt());
    }

    @Test
    void paymentUsesAuthoritativeOutstandingBalanceAndRejectsSecondPayment() {
        invoiceAug.applySiigoBalance(Money.of(1_190_000), java.time.Instant.now(), null);
        repositorioFactura.saveAndFlush(invoiceAug);
        var sim = paymentService.simulate(new PaymentService.PaymentSimulationCommand(testClient.getNit(), List.of(invoiceAug.getId()), List.of()));
        assertEquals(Money.of(1_190_000), sim.totalInvoices());
        var cmd = new PaymentService.PaymentRegistrationCommand(testClient.getNit(), List.of(invoiceAug.getId()), List.of(),
                LocalDate.now(), "TRANSFERENCIA", "11100501", "BALANCE-" + UUID.randomUUID(), null);
        var first = paymentService.register(cmd);
        assertEquals(Money.of(1_190_000), first.netPaidAmount());
        assertEquals(first.paymentId(), paymentService.register(cmd).paymentId());
        var duplicate = new PaymentService.PaymentRegistrationCommand(testClient.getNit(), List.of(invoiceAug.getId()), List.of(),
                LocalDate.now(), "TRANSFERENCIA", "11100501", "SECOND-" + UUID.randomUUID(), null);
        assertThrows(PaymentConflictException.class, () -> paymentService.register(duplicate));
    }

    @Test
    void duplicatedInvoiceIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> paymentService.simulate(new PaymentService.PaymentSimulationCommand(
                testClient.getNit(), List.of(invoiceAug.getId(), invoiceAug.getId()), List.of())));
    }

    @Test
    void partialBalanceCannotUseEstimatedWithholdingBasesForRealCollection() {
        invoiceAug.applySiigoBalance(Money.of(1_190_000), java.time.Instant.now(), null);
        repositorioFactura.saveAndFlush(invoiceAug);
        assertThrows(PaymentConflictException.class, () -> paymentService.simulate(
                new PaymentService.PaymentSimulationCommand(testClient.getNit(), List.of(invoiceAug.getId()), List.of(at(rfConcept)))));
    }

    @Test
    void checkoutApprovalRequiresProviderAmountAndNeverAssumesIt() {
        var checkout = paymentService.checkout(new PaymentService.CheckoutCommand(testClient.getNit(), List.of(invoiceAug.getId()), List.of()));
        assertThrows(PaymentConflictException.class, () -> paymentService.confirmPayment(new PaymentService.PaymentConfirmationCommand(
                testClient.getNit(), "MISSING-AMOUNT", List.of(), List.of(), null, LocalDate.now(), "PSE", "BANCO",
                null, null, null, null, checkout.paymentId(), "APPROVED")));
        assertEquals(InvoiceStatus.CONSOLIDATED, invoiceAug.getStatus());
    }

    @Test
    void directConfirmationCannotReuseTransactionWithChangedAmount() {
        String transaction = "DIRECT-" + UUID.randomUUID();
        var original = new PaymentService.PaymentConfirmationCommand(testClient.getNit(), transaction, null, List.of(invoiceAug.getId()),
                new BigDecimal("500000"), LocalDate.now(), "PSE", "BANCO", null, null, null, null);
        var first = paymentService.confirmPayment(original);
        assertEquals(first.paymentId(), paymentService.confirmPayment(original).paymentId());
        var changed = new PaymentService.PaymentConfirmationCommand(testClient.getNit(), transaction, null, List.of(invoiceAug.getId()),
                new BigDecimal("600000"), LocalDate.now(), "PSE", "BANCO", null, null, null, null);
        assertThrows(PaymentConflictException.class, () -> paymentService.confirmPayment(changed));
    }

    @Test
    void changedAmountDoesNotSettleInvoice() {
        var cmd = new PaymentService.PaymentRegistrationCommand(testClient.getNit(), List.of(invoiceAug.getId()), List.of(),
                LocalDate.now(), "TRANSFERENCIA", "11100501", "STALE-" + UUID.randomUUID(), null,
                UUID.randomUUID().toString(), Money.of(1), List.of());
        assertThrows(PaymentConflictException.class, () -> paymentService.register(cmd));
        assertEquals(InvoiceStatus.CONSOLIDATED, invoiceAug.getStatus());
    }

    @Test
    void manualDeductionIsFrozenWithoutChangingCatalog() {
        var edit = new com.sistemaspalacios.billing.domain.vo.PaymentDeduction(rfConcept.getId(), null, Money.of(12_345), "Valor informado por cliente");
        var cmd = new PaymentService.PaymentRegistrationCommand(testClient.getNit(), List.of(invoiceAug.getId()), List.of(at(rfConcept)),
                LocalDate.now(), "TRANSFERENCIA", "11100501", "EDIT-" + UUID.randomUUID(), null,
                UUID.randomUUID().toString(), null, List.of(edit));
        var result = paymentService.register(cmd);
        assertEquals(Money.of(12_345), result.totalWithholdings());
        assertEquals(0, rfConcept.getMaxPercentage().compareTo(new BigDecimal("4")));
        assertEquals(Money.of(12_345), paymentService.getPayment(result.paymentId()).getWithholdings().getFirst().getWithheldAmount());
    }

    private static PaymentService.ConceptSelection at(TaxConceptValidity concept) {
        return new PaymentService.ConceptSelection(concept.getId(), concept.getMaxPercentage());
    }
}
