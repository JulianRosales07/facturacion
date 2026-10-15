package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.dto.FacturaSpPagosDto;
import com.sistemaspalacios.billing.service.dto.OfficialInvoiceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas obligatorias de lógica de negocio:
 * Traslado y consolidación de cartera pendiente entre periodos.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class CarteraPendientePeriodosTest {

    @Autowired
    private OfficialInvoiceImportService importer;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private SpPagosQueryService spPagosQueryService;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioPago repositorioPago;

    private String testNit;

    @BeforeEach
    void setUp() {
        testNit = "NIT-TEST-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private OfficialInvoiceData createInvoiceData(String nit, String number, YearMonth servicePeriod, BigDecimal total) {
        BigDecimal subtotal = total.divide(new BigDecimal("1.19"), 2, RoundingMode.HALF_UP);
        BigDecimal iva = total.subtract(subtotal);
        LocalDate issueDate = servicePeriod.atDay(15);
        LocalDate dueDate = servicePeriod.atEndOfMonth();
        String description = "Servicio empresarial fibra optica GR 01-31 " + monthCode(servicePeriod.getMonthValue()) + " " + servicePeriod.getYear();
        return new OfficialInvoiceData(
                nit,
                "CLIENTE CORPORATIVO " + nit,
                BillingType.F1,
                number,
                issueDate,
                dueDate,
                "CUFE-" + number + "-" + servicePeriod,
                Money.of(total),
                List.of(new OfficialInvoiceData.Item("SERV-CORP", description, BigDecimal.ONE, Money.of(subtotal), Money.of(iva)))
        );
    }

    private String monthCode(int month) {
        return switch (month) {
            case 1 -> "ENE"; case 2 -> "FEB"; case 3 -> "MAR"; case 4 -> "ABR";
            case 5 -> "MAY"; case 6 -> "JUN"; case 7 -> "JUL"; case 8 -> "AGT";
            case 9 -> "SEP"; case 10 -> "OCT"; case 11 -> "NOV"; case 12 -> "DIC";
            default -> "OCT";
        };
    }

    @Test
    @DisplayName("1. Factura de octubre pendiente e importación de factura de noviembre")
    void testFacturaOctubrePendienteEImportacionNoviembre() {
        // Octubre: $1.000.000
        OfficialInvoiceData octData = createInvoiceData(testNit, "88001", YearMonth.of(2026, 10), new BigDecimal("1000000.00"));
        Invoice octInvoice = importer.importDocument(octData);
        assertNotNull(octInvoice.getId());
        assertEquals(Money.of("1000000.00"), octInvoice.getTotalAmount());
        assertEquals(Money.of("1000000.00"), octInvoice.getPendingBalance());

        // Noviembre: $1.000.000
        OfficialInvoiceData novData = createInvoiceData(testNit, "88002", YearMonth.of(2026, 11), new BigDecimal("1000000.00"));
        Invoice novInvoice = importer.importDocument(novData);
        assertNotNull(novInvoice.getId());
        assertNotEquals(octInvoice.getId(), novInvoice.getId(), "Deben ser obligaciones independientes");

        // Cartera del cliente: 2 facturas pendientes, total $2.000.000
        PaymentService.ClientPortfolioSummary portfolio = paymentService.getClientPortfolio(testNit);
        assertEquals(2, portfolio.totalPendingInvoices());
        assertEquals(Money.of("2000000.00"), portfolio.totalOutstandingDebt());

        // JSON SP Pagos de noviembre refleja la factura actual ($1.000.000), cartera anterior ($1.000.000) y total ($2.000.000)
        FacturaSpPagosDto spPagosNov = spPagosQueryService.getInvoiceForPayment(novInvoice.getId());
        assertEquals(new BigDecimal("1000000.00"), spPagosNov.importes().totalOficial());
        assertEquals(new BigDecimal("1000000.00"), spPagosNov.importes().saldoPendiente());
        assertEquals(new BigDecimal("1000000.00"), spPagosNov.importes().carteraAnteriorPendiente());
        assertEquals(new BigDecimal("2000000.00"), spPagosNov.importes().saldoTotalPorCobrar());
        assertNotNull(spPagosNov.cartera());
        assertEquals(1, spPagosNov.cartera().obligacionesAnteriores().size());
        assertEquals("88001", spPagosNov.cartera().obligacionesAnteriores().getFirst().numeroFactura());
    }

    @Test
    @DisplayName("2. Factura de octubre pagada e importación de noviembre")
    void testFacturaOctubrePagadaEImportacionNoviembre() {
        // Octubre: $1.000.000
        OfficialInvoiceData octData = createInvoiceData(testNit, "88101", YearMonth.of(2026, 10), new BigDecimal("1000000.00"));
        Invoice octInvoice = importer.importDocument(octData);

        // Pago completo de octubre
        PaymentService.PaymentConfirmationCommand cmd = new PaymentService.PaymentConfirmationCommand(
                testNit, "TX-PAGO-OCT", List.of("F1:88101"), new BigDecimal("1000000.00")
        );
        PaymentService.PaymentResult pagoResult = paymentService.confirmPayment(cmd);
        assertNotNull(pagoResult);
        assertEquals(0, pagoResult.remainingPendingInvoicesCount());

        // Noviembre: $1.000.000
        OfficialInvoiceData novData = createInvoiceData(testNit, "88102", YearMonth.of(2026, 11), new BigDecimal("1000000.00"));
        Invoice novInvoice = importer.importDocument(novData);

        // Cartera del cliente: solo noviembre pendiente ($1.000.000), octubre pagada sin deuda
        PaymentService.ClientPortfolioSummary portfolio = paymentService.getClientPortfolio(testNit);
        assertEquals(1, portfolio.totalPendingInvoices());
        assertEquals(Money.of("1000000.00"), portfolio.totalOutstandingDebt());

        FacturaSpPagosDto spPagosNov = spPagosQueryService.getInvoiceForPayment(novInvoice.getId());
        assertEquals(0, new BigDecimal("1000000.00").compareTo(spPagosNov.importes().saldoPendiente()));
        assertEquals(0, BigDecimal.ZERO.compareTo(spPagosNov.importes().carteraAnteriorPendiente()));
        assertEquals(0, new BigDecimal("1000000.00").compareTo(spPagosNov.importes().saldoTotalPorCobrar()));
        assertTrue(spPagosNov.cartera().obligacionesAnteriores().isEmpty());
    }

    @Test
    @DisplayName("3. Factura de octubre parcialmente pagada e importación de noviembre")
    void testFacturaOctubreParcialmentePagadaEImportacionNoviembre() {
        // Octubre: $1.000.000
        OfficialInvoiceData octData = createInvoiceData(testNit, "88201", YearMonth.of(2026, 10), new BigDecimal("1000000.00"));
        Invoice octInvoice = importer.importDocument(octData);

        // Pago parcial de $400.000 en octubre
        PaymentService.PaymentConfirmationCommand cmd = new PaymentService.PaymentConfirmationCommand(
                testNit, "TX-ABONO-OCT", List.of("F1:88201"), new BigDecimal("400000.00")
        );
        PaymentService.PaymentResult pagoResult = paymentService.confirmPayment(cmd);
        assertTrue(pagoResult.partialPayment());
        assertEquals(Money.of("600000.00"), pagoResult.remainingPendingDebt());

        // Noviembre: $1.000.000
        OfficialInvoiceData novData = createInvoiceData(testNit, "88202", YearMonth.of(2026, 11), new BigDecimal("1000000.00"));
        Invoice novInvoice = importer.importDocument(novData);

        // Total cartera: $600.000 + $1.000.000 = $1.600.000
        PaymentService.ClientPortfolioSummary portfolio = paymentService.getClientPortfolio(testNit);
        assertEquals(2, portfolio.totalPendingInvoices());
        assertEquals(Money.of("1600000.00"), portfolio.totalOutstandingDebt());

        FacturaSpPagosDto spPagosNov = spPagosQueryService.getInvoiceForPayment(novInvoice.getId());
        assertEquals(new BigDecimal("1000000.00"), spPagosNov.importes().saldoPendiente());
        assertEquals(new BigDecimal("600000.00"), spPagosNov.importes().carteraAnteriorPendiente());
        assertEquals(new BigDecimal("1600000.00"), spPagosNov.importes().saldoTotalPorCobrar());
    }

    @Test
    @DisplayName("4. Varias facturas pendientes del mismo cliente en distintos periodos")
    void testVariasFacturasPendientesMismoClienteDistintosPeriodos() {
        Invoice ago = importer.importDocument(createInvoiceData(testNit, "88301", YearMonth.of(2026, 8), new BigDecimal("500000.00")));
        Invoice sep = importer.importDocument(createInvoiceData(testNit, "88302", YearMonth.of(2026, 9), new BigDecimal("300000.00")));
        Invoice oct = importer.importDocument(createInvoiceData(testNit, "88303", YearMonth.of(2026, 10), new BigDecimal("200000.00")));
        Invoice nov = importer.importDocument(createInvoiceData(testNit, "88304", YearMonth.of(2026, 11), new BigDecimal("1000000.00")));

        PaymentService.ClientPortfolioSummary portfolio = paymentService.getClientPortfolio(testNit);
        assertEquals(4, portfolio.totalPendingInvoices());
        assertEquals(Money.of("2000000.00"), portfolio.totalOutstandingDebt());

        FacturaSpPagosDto spPagosNov = spPagosQueryService.getInvoiceForPayment(nov.getId());
        assertEquals(new BigDecimal("1000000.00"), spPagosNov.importes().saldoPendiente());
        assertEquals(new BigDecimal("1000000.00"), spPagosNov.importes().carteraAnteriorPendiente()); // 500k + 300k + 200k
        assertEquals(new BigDecimal("2000000.00"), spPagosNov.importes().saldoTotalPorCobrar());
        assertEquals(3, spPagosNov.cartera().obligacionesAnteriores().size());
    }

    @Test
    @DisplayName("5. Reimportación del mismo Excel o PDF sin duplicar obligaciones")
    void testReimportacionMismoExcelOPdfSinDuplicarObligaciones() {
        OfficialInvoiceData octData = createInvoiceData(testNit, "88401", YearMonth.of(2026, 10), new BigDecimal("1000000.00"));
        Invoice first = importer.importDocument(octData);
        Invoice second = importer.importDocument(octData);

        assertEquals(first.getId(), second.getId(), "La reimportación debe ser idempotente y devolver la misma entidad");

        PaymentService.ClientPortfolioSummary portfolio = paymentService.getClientPortfolio(testNit);
        assertEquals(1, portfolio.totalPendingInvoices());
        assertEquals(Money.of("1000000.00"), portfolio.totalOutstandingDebt());
    }

    @Test
    @DisplayName("6. Factura antigua importada después de una factura más reciente")
    void testFacturaAntiguaImportadaDespuesDeFacturaMasReciente() {
        // Se importa noviembre primero
        OfficialInvoiceData novData = createInvoiceData(testNit, "88502", YearMonth.of(2026, 11), new BigDecimal("1000000.00"));
        Invoice nov = importer.importDocument(novData);

        // Luego se importa octubre
        OfficialInvoiceData octData = createInvoiceData(testNit, "88501", YearMonth.of(2026, 10), new BigDecimal("1000000.00"));
        Invoice oct = importer.importDocument(octData);

        assertNotEquals(nov.getId(), oct.getId());

        // Cartera consolidada por cliente: $2.000.000
        PaymentService.ClientPortfolioSummary portfolio = paymentService.getClientPortfolio(testNit);
        assertEquals(2, portfolio.totalPendingInvoices());
        assertEquals(Money.of("2000000.00"), portfolio.totalOutstandingDebt());

        // Al consultar noviembre, el sistema reconoce que octubre es anterior
        FacturaSpPagosDto spPagosNov = spPagosQueryService.getInvoiceForPayment(nov.getId());
        assertEquals(new BigDecimal("1000000.00"), spPagosNov.importes().carteraAnteriorPendiente());
        assertEquals(new BigDecimal("2000000.00"), spPagosNov.importes().saldoTotalPorCobrar());
    }

    @Test
    @DisplayName("7. Pago parcial o total y actualización correcta de saldos")
    void testPagoParcialOTotalYActualizacionCorrectaSaldos() {
        Invoice oct = importer.importDocument(createInvoiceData(testNit, "88601", YearMonth.of(2026, 10), new BigDecimal("1000000.00")));
        Invoice nov = importer.importDocument(createInvoiceData(testNit, "88602", YearMonth.of(2026, 11), new BigDecimal("1000000.00")));

        // Pago total de octubre
        PaymentService.PaymentConfirmationCommand payOct = new PaymentService.PaymentConfirmationCommand(
                testNit, "TX-PAGO-FULL-OCT", List.of("F1:88601"), new BigDecimal("1000000.00")
        );
        paymentService.confirmPayment(payOct);

        Invoice octAfter = repositorioFactura.findById(oct.getId()).orElseThrow();
        assertEquals(InvoiceStatus.PAID, octAfter.getStatus());
        assertEquals(Money.ZERO, octAfter.getPendingBalance());

        // Noviembre sigue pendiente con saldo intacto
        Invoice novAfter = repositorioFactura.findById(nov.getId()).orElseThrow();
        assertEquals(InvoiceStatus.CONSOLIDATED, novAfter.getStatus());
        assertEquals(Money.of("1000000.00"), novAfter.getPendingBalance());

        PaymentService.ClientPortfolioSummary portfolio = paymentService.getClientPortfolio(testNit);
        assertEquals(1, portfolio.totalPendingInvoices());
        assertEquals(Money.of("1000000.00"), portfolio.totalOutstandingDebt());
    }

    @Test
    @DisplayName("8. Construcción del JSON de SP Pagos sin duplicar importes")
    void testConstruccionJsonSpPagosSinDuplicarImportes() {
        Invoice oct = importer.importDocument(createInvoiceData(testNit, "88701", YearMonth.of(2026, 10), new BigDecimal("1000000.00")));
        Invoice nov = importer.importDocument(createInvoiceData(testNit, "88702", YearMonth.of(2026, 11), new BigDecimal("1000000.00")));

        FacturaSpPagosDto json = spPagosQueryService.getInvoiceForPayment(nov.getId());

        // La obligación oficial del documento nov no se altera
        assertEquals(new BigDecimal("1000000.00"), json.importes().totalOficial());
        assertEquals(new BigDecimal("1000000.00"), json.importes().saldoPendiente());
        // Se informa la cartera anterior de manera explícita y separada
        assertEquals(new BigDecimal("1000000.00"), json.importes().carteraAnteriorPendiente());
        assertEquals(new BigDecimal("2000000.00"), json.importes().saldoTotalPorCobrar());

        // Los items corresponden exclusivamente a la factura de noviembre (no hay items ficticios de deuda anterior)
        assertEquals(1, json.items().size());
        assertEquals("SERV-CORP", json.items().getFirst().codigo());
    }

    @Test
    @DisplayName("9. Dos facturas del mismo NIT con diferentes números oficiales y periodos")
    void testDosFacturasMismoNitConDiferentesNumerosOficialesYPeriodos() {
        Invoice doc1 = importer.importDocument(createInvoiceData(testNit, "88801", YearMonth.of(2026, 10), new BigDecimal("850000.00")));
        Invoice doc2 = importer.importDocument(createInvoiceData(testNit, "88802", YearMonth.of(2026, 11), new BigDecimal("950000.00")));

        assertEquals(testNit, doc1.getClientNit());
        assertEquals(testNit, doc2.getClientNit());
        assertNotEquals(doc1.getInvoiceNumber(), doc2.getInvoiceNumber());
        assertNotEquals(doc1.getPeriodId(), doc2.getPeriodId());

        List<Invoice> facturasCliente = repositorioFactura.findByClientNit(testNit);
        assertEquals(2, facturasCliente.size());
    }

    @Test
    @DisplayName("10. Conservación de las facturas, pagos e importaciones históricas")
    void testConservacionFacturasPagosEImportacionesHistoricas() {
        Invoice doc1 = importer.importDocument(createInvoiceData(testNit, "88901", YearMonth.of(2026, 9), new BigDecimal("500000.00")));
        PaymentService.PaymentConfirmationCommand payCmd = new PaymentService.PaymentConfirmationCommand(
                testNit, "TX-HIST-PAY", List.of("F1:88901"), new BigDecimal("500000.00")
        );
        paymentService.confirmPayment(payCmd);

        // Se importa nuevo periodo
        Invoice doc2 = importer.importDocument(createInvoiceData(testNit, "88902", YearMonth.of(2026, 10), new BigDecimal("700000.00")));

        // El registro de la factura 1 y sus pagos siguen intactos
        Invoice histDoc1 = repositorioFactura.findById(doc1.getId()).orElseThrow();
        assertEquals(InvoiceStatus.PAID, histDoc1.getStatus());
        assertEquals(Money.of("500000.00"), histDoc1.getTotalAmount());
        assertFalse(repositorioPago.findAppliedPaymentsByInvoiceId(doc1.getId()).isEmpty());

        // El nuevo documento existe normalmente
        Invoice newDoc2 = repositorioFactura.findById(doc2.getId()).orElseThrow();
        assertEquals(InvoiceStatus.CONSOLIDATED, newDoc2.getStatus());
        assertEquals(Money.of("700000.00"), newDoc2.getPendingBalance());
    }
}
