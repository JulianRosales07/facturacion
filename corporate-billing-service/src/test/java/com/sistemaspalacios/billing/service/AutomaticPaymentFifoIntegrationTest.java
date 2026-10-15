package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.Payment;
import com.sistemaspalacios.billing.domain.model.PaymentInvoice;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.PaymentService.PaymentConfirmationCommand;
import com.sistemaspalacios.billing.service.PaymentService.PaymentResult;
import com.sistemaspalacios.billing.service.dto.FacturaSpPagosDto;
import com.sistemaspalacios.billing.service.dto.OfficialInvoiceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración obligatorias para la Fase 2:
 * Aplicación automática de pagos generales (FIFO), pagos explícitos,
 * manejo de excedentes, idempotencia, concurrencia y coherencia contable.
 */
@SpringBootTest
@ActiveProfiles("postgres")
class AutomaticPaymentFifoIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.currentTimeMillis() % 100000000) + 100000);

    private static String nextInvoiceNumber() {
        return String.valueOf(Math.abs(SEQ.incrementAndGet()));
    }

    private static String nextTxId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

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
        testNit = "NIT-FIFO-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private OfficialInvoiceData createInvoiceData(String nit, String number, YearMonth servicePeriod, BigDecimal total) {
        BigDecimal subtotal = total.divide(new BigDecimal("1.19"), 2, RoundingMode.HALF_UP);
        BigDecimal iva = total.subtract(subtotal);
        LocalDate issueDate = servicePeriod.atDay(15);
        LocalDate dueDate = servicePeriod.atEndOfMonth();
        String description = "Servicio empresarial fibra optica GR 01-31 " + monthCode(servicePeriod.getMonthValue()) + " " + servicePeriod.getYear();
        return new OfficialInvoiceData(
                nit,
                "EMPRESA TEST FIFO " + nit,
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
    @Transactional
    @DisplayName("1. Pago general inferior a la cartera total, aplicado FIFO")
    void testPagoGeneralInferiorACarteraTotalAplicadoFifo() {
        // Octubre: $1.000.000, Noviembre: $1.000.000. Total cartera: $2.000.000
        Invoice oct = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 10), new BigDecimal("1000000.00")));
        Invoice nov = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 11), new BigDecimal("1000000.00")));

        // Pago general de $600.000 (sin invoiceIds ni invoiceKeys)
        PaymentResult result = paymentService.confirmPayment(new PaymentConfirmationCommand(
                testNit, nextTxId("TX-GEN-01"), null, null, new BigDecimal("600000.00"),
                LocalDate.of(2026, 11, 20), "PASARELA", "BANCOLOMBIA", null, null, "Pago general parcial", null
        ));

        assertNotNull(result);
        assertEquals(Money.of(new BigDecimal("600000.00")), result.grossAmount());
        assertEquals(Money.ZERO, result.unappliedAmount());
        assertTrue(result.partialPayment());
        assertEquals(2, result.remainingPendingInvoicesCount());
        assertEquals(Money.of(new BigDecimal("1400000.00")), result.remainingPendingDebt());

        // Verificar que FIFO aplicó los $600.000 a Octubre (la más antigua) y $0 a Noviembre
        Invoice octActual = repositorioFactura.findById(oct.getId()).orElseThrow();
        Invoice novActual = repositorioFactura.findById(nov.getId()).orElseThrow();

        assertEquals(Money.of(new BigDecimal("600000.00")), octActual.getPaidAmount());
        assertEquals(Money.of(new BigDecimal("400000.00")), octActual.getPendingBalance());
        assertNotEquals(InvoiceStatus.PAID, octActual.getStatus());

        assertEquals(Money.ZERO, novActual.getPaidAmount());
        assertEquals(Money.of(new BigDecimal("1000000.00")), novActual.getPendingBalance());
    }

    @Test
    @Transactional
    @DisplayName("2. Pago que extingue la factura más antigua y deja saldo en la siguiente")
    void testPagoExtingueFacturaAntiguaYDejaSaldoEnSiguiente() {
        // Octubre: $1.000.000, Noviembre: $1.000.000. Total cartera: $2.000.000
        Invoice oct = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 10), new BigDecimal("1000000.00")));
        Invoice nov = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 11), new BigDecimal("1000000.00")));

        // Pago general de $1.500.000
        PaymentResult result = paymentService.confirmPayment(new PaymentConfirmationCommand(
                testNit, nextTxId("TX-GEN-02"), null, null, new BigDecimal("1500000.00"),
                LocalDate.of(2026, 11, 22), "PASARELA", "BANCOLOMBIA", null, null, "Pago general FIFO", null
        ));

        assertNotNull(result);
        assertEquals(Money.of(new BigDecimal("1500000.00")), result.grossAmount());
        assertEquals(Money.ZERO, result.unappliedAmount());
        assertTrue(result.partialPayment());
        assertEquals(1, result.remainingPendingInvoicesCount());
        assertEquals(Money.of(new BigDecimal("500000.00")), result.remainingPendingDebt());

        Invoice octActual = repositorioFactura.findById(oct.getId()).orElseThrow();
        Invoice novActual = repositorioFactura.findById(nov.getId()).orElseThrow();

        // Octubre debe quedar PAGADA con saldo 0
        assertEquals(InvoiceStatus.PAID, octActual.getStatus());
        assertEquals(Money.of(new BigDecimal("1000000.00")), octActual.getPaidAmount());
        assertEquals(Money.ZERO, octActual.getPendingBalance());

        // Noviembre debe quedar con abono parcial de $500.000 y saldo restante de $500.000
        assertNotEquals(InvoiceStatus.PAID, novActual.getStatus());
        assertEquals(Money.of(new BigDecimal("500000.00")), novActual.getPaidAmount());
        assertEquals(Money.of(new BigDecimal("500000.00")), novActual.getPendingBalance());
    }

    @Test
    @Transactional
    @DisplayName("3. Pago vinculado explícitamente a una factura posterior, sin aplicar FIFO")
    void testPagoVinculadoExplicitamenteAFacturaPosteriorSinAplicarFifo() {
        // Octubre: $1.000.000 (más antigua), Noviembre: $1.000.000 (posterior)
        Invoice oct = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 10), new BigDecimal("1000000.00")));
        Invoice nov = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 11), new BigDecimal("1000000.00")));

        // Pago explícito vinculado ÚNICAMENTE a Noviembre por $1.000.000
        PaymentResult result = paymentService.confirmPayment(new PaymentConfirmationCommand(
                testNit, nextTxId("TX-EXP-03"), null, List.of(nov.getId()), new BigDecimal("1000000.00"),
                LocalDate.of(2026, 11, 23), "PASARELA", "BANCOLOMBIA", null, null, "Pago explícito a Noviembre", null
        ));

        assertNotNull(result);
        assertEquals(List.of(nov.getId()), result.paidInvoiceIds());

        Invoice octActual = repositorioFactura.findById(oct.getId()).orElseThrow();
        Invoice novActual = repositorioFactura.findById(nov.getId()).orElseThrow();

        // Noviembre debe quedar extinguida
        assertEquals(InvoiceStatus.PAID, novActual.getStatus());
        assertEquals(Money.ZERO, novActual.getPendingBalance());

        // Octubre NO debió recibir dinero porque el pago fue explícito (excepción a FIFO)
        assertNotEquals(InvoiceStatus.PAID, octActual.getStatus());
        assertEquals(Money.of(new BigDecimal("1000000.00")), octActual.getPendingBalance());
        assertEquals(Money.ZERO, octActual.getPaidAmount());
    }

    @Test
    @Transactional
    @DisplayName("4. Pago parcial de una factura")
    void testPagoParcialDeUnaFactura() {
        Invoice inv = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 10), new BigDecimal("1000000.00")));

        PaymentResult result = paymentService.confirmPayment(new PaymentConfirmationCommand(
                testNit, nextTxId("TX-PARTIAL-04"), null, List.of(inv.getId()), new BigDecimal("350000.00"),
                LocalDate.of(2026, 10, 20), "PASARELA", "BANCOLOMBIA", null, null, "Abono parcial", null
        ));

        assertTrue(result.partialPayment());
        assertEquals(1, result.remainingPendingInvoicesCount());
        assertEquals(Money.of(new BigDecimal("650000.00")), result.remainingPendingDebt());

        Invoice actual = repositorioFactura.findById(inv.getId()).orElseThrow();
        assertNotEquals(InvoiceStatus.PAID, actual.getStatus());
        assertEquals(Money.of(new BigDecimal("350000.00")), actual.getPaidAmount());
        assertEquals(Money.of(new BigDecimal("650000.00")), actual.getPendingBalance());
    }

    @Test
    @Transactional
    @DisplayName("5. Pago que cubre exactamente toda la cartera")
    void testPagoCubreExactamenteTodaLaCartera() {
        Invoice oct = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 10), new BigDecimal("1000000.00")));
        Invoice nov = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 11), new BigDecimal("1000000.00")));

        PaymentResult result = paymentService.confirmPayment(new PaymentConfirmationCommand(
                testNit, nextTxId("TX-FULL-05"), null, null, new BigDecimal("2000000.00"),
                LocalDate.of(2026, 11, 25), "PASARELA", "BANCOLOMBIA", null, null, "Pago total de cartera", null
        ));

        assertFalse(result.partialPayment());
        assertEquals(0, result.remainingPendingInvoicesCount());
        assertEquals(Money.ZERO, result.remainingPendingDebt());
        assertEquals(Money.ZERO, result.unappliedAmount());

        Invoice octActual = repositorioFactura.findById(oct.getId()).orElseThrow();
        Invoice novActual = repositorioFactura.findById(nov.getId()).orElseThrow();

        assertEquals(InvoiceStatus.PAID, octActual.getStatus());
        assertEquals(InvoiceStatus.PAID, novActual.getStatus());
        assertEquals(Money.ZERO, octActual.getPendingBalance());
        assertEquals(Money.ZERO, novActual.getPendingBalance());
    }

    @Test
    @Transactional
    @DisplayName("6. Pago superior a la cartera pendiente y tratamiento del excedente")
    void testPagoSuperiorACarteraPendienteYTratamientoExcedente() {
        // Cartera total = $2.000.000 ($1.000.000 c/u)
        Invoice oct = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 10), new BigDecimal("1000000.00")));
        Invoice nov = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 11), new BigDecimal("1000000.00")));

        // Pago superior de $2.500.000 (excedente de $500.000)
        PaymentResult result = paymentService.confirmPayment(new PaymentConfirmationCommand(
                testNit, nextTxId("TX-EXCESS-06"), null, null, new BigDecimal("2500000.00"),
                LocalDate.of(2026, 11, 26), "PASARELA", "BANCOLOMBIA", null, null, "Pago con excedente", null
        ));

        assertNotNull(result);
        assertFalse(result.partialPayment());
        assertEquals(0, result.remainingPendingInvoicesCount());
        assertEquals(Money.ZERO, result.remainingPendingDebt());

        // El excedente de $500.000 se reporta sin aplicar a deudas inexistentes
        assertEquals(Money.of(new BigDecimal("500000.00")), result.unappliedAmount());

        // Verificar que el pago en base de datos registró la observación de remanente
        Payment payment = repositorioPago.findById(result.paymentId()).orElseThrow();
        assertTrue(payment.getObservation().contains("Excedente no aplicado registrado para conciliación/anticipo: $500.000,00")
                || payment.getObservation().contains("500000") || payment.getObservation().contains("500.000"));

        // Ambas facturas quedan exactamente en cero y PAGADAS
        Invoice octActual = repositorioFactura.findById(oct.getId()).orElseThrow();
        Invoice novActual = repositorioFactura.findById(nov.getId()).orElseThrow();
        assertEquals(InvoiceStatus.PAID, octActual.getStatus());
        assertEquals(InvoiceStatus.PAID, novActual.getStatus());
        assertEquals(Money.ZERO, octActual.getPendingBalance());
        assertEquals(Money.ZERO, novActual.getPendingBalance());
    }

    @Test
    @Transactional
    @DisplayName("7. Reprocesamiento de una notificación de pago sin duplicar aplicaciones")
    void testReprocesamientoNotificacionPagoSinDuplicarAplicaciones() {
        Invoice oct = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 10), new BigDecimal("1000000.00")));

        String txId = nextTxId("TX-IDEMP-07");
        String idemKey = "IDEMP-" + UUID.randomUUID();

        PaymentConfirmationCommand cmd = new PaymentConfirmationCommand(
                testNit, txId, null, List.of(oct.getId()), new BigDecimal("1000000.00"),
                LocalDate.of(2026, 10, 25), "PASARELA", "BANCOLOMBIA", null, null, "Notificación pasarela", idemKey
        );

        PaymentResult first = paymentService.confirmPayment(cmd);
        assertNotNull(first);

        // Reprocesar exactamente la misma transacción
        PaymentResult second = paymentService.confirmPayment(cmd);
        assertNotNull(second);

        assertEquals(first.paymentId(), second.paymentId());
        assertEquals(first.grossAmount(), second.grossAmount());

        // Comprobar que en la base de datos solo existe 1 pago para la transacción
        List<Payment> payments = repositorioPago.findAppliedPaymentsByInvoiceId(oct.getId());
        assertEquals(1, payments.size());

        Invoice octActual = repositorioFactura.findById(oct.getId()).orElseThrow();
        assertEquals(Money.of(new BigDecimal("1000000.00")), octActual.getPaidAmount());
        assertEquals(Money.ZERO, octActual.getPendingBalance());
        assertEquals(InvoiceStatus.PAID, octActual.getStatus());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("8. Dos confirmaciones concurrentes sin sobreaplicación del saldo")
    void testDosConfirmacionesConcurrentesSinSobreaplicacionDelSaldo() throws Exception {
        Invoice oct = importer.importDocument(createInvoiceData(testNit, nextInvoiceNumber(), YearMonth.of(2026, 10), new BigDecimal("1000000.00")));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<PaymentResult> f1 = executor.submit(() -> {
            startLatch.await();
            return paymentService.confirmPayment(new PaymentConfirmationCommand(
                    testNit, nextTxId("TX-CONC-8A"), null, null, new BigDecimal("700000.00"),
                    LocalDate.of(2026, 10, 27), "PASARELA", "BANCOLOMBIA", null, null, "Pago concurrente A", null
            ));
        });

        Future<PaymentResult> f2 = executor.submit(() -> {
            startLatch.await();
            return paymentService.confirmPayment(new PaymentConfirmationCommand(
                    testNit, nextTxId("TX-CONC-8B"), null, null, new BigDecimal("700000.00"),
                    LocalDate.of(2026, 10, 27), "PASARELA", "BANCOLOMBIA", null, null, "Pago concurrente B", null
            ));
        });

        startLatch.countDown();
        PaymentResult r1 = f1.get(10, TimeUnit.SECONDS);
        PaymentResult r2 = f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertNotNull(r1);
        assertNotNull(r2);

        // Verificar estado y saldo final de la factura en DB
        Invoice octActual = repositorioFactura.findById(oct.getId()).orElseThrow();
        assertEquals(InvoiceStatus.PAID, octActual.getStatus());
        assertEquals(Money.ZERO, octActual.getPendingBalance());

        // Comprobar que entre ambos pagos el total efectivamente aplicado a la factura fue exactamente $1.000.000
        Money applied1 = r1.grossAmount().minus(r1.unappliedAmount());
        Money applied2 = r2.grossAmount().minus(r2.unappliedAmount());
        assertEquals(Money.of(new BigDecimal("1000000.00")), applied1.plus(applied2));

        // Entre ambos pagos ($1.400.000 total), $1.000.000 extinguió la factura y $400.000 quedó como excedente no aplicado
        Money totalUnapplied = r1.unappliedAmount().plus(r2.unappliedAmount());
        assertEquals(Money.of(new BigDecimal("400000.00")), totalUnapplied);
    }

    @Test
    @Transactional
    @DisplayName("9. Conservación del historial y de los importes oficiales")
    void testConservacionHistorialEImportesOficiales() {
        BigDecimal total = new BigDecimal("1190000.00");
        String invNum = nextInvoiceNumber();
        Invoice oct = importer.importDocument(createInvoiceData(testNit, invNum, YearMonth.of(2026, 10), total));

        Money originalSubtotal = oct.getSubtotalAmount();
        Money originalIva = oct.getIvaAmount();
        Money originalTotal = oct.getTotalAmount();
        String originalCufe = oct.getCufe();

        // Aplicar abono parcial
        paymentService.confirmPayment(new PaymentConfirmationCommand(
                testNit, nextTxId("TX-HIST-09"), null, List.of(oct.getId()), new BigDecimal("500000.00"),
                LocalDate.of(2026, 10, 28), "PASARELA", "BANCOLOMBIA", null, null, "Abono parcial histórico", null
        ));

        Invoice octAfter = repositorioFactura.findById(oct.getId()).orElseThrow();

        // Los valores oficiales deben ser estrictamente inmutables
        assertEquals(originalSubtotal, octAfter.getSubtotalAmount());
        assertEquals(originalIva, octAfter.getIvaAmount());
        assertEquals(originalTotal, octAfter.getTotalAmount());
        assertEquals(originalCufe, octAfter.getCufe());
        assertEquals(1, octAfter.getItems().size());
        assertEquals("CUFE-" + invNum + "-2026-10", octAfter.getCufe());
    }

    @Test
    @Transactional
    @DisplayName("10. Coherencia entre pagos aplicados, saldo de cada factura y saldo consolidado del cliente")
    void testCoherenciaEntrePagosAplicadosSaldoFacturasYConsolidado() {
        // Tres facturas:
        // Septiembre: $500.000
        // Octubre:    $1.000.000
        // Noviembre:  $800.000
        // Total inicial de cartera: $2.300.000
        String sepNum = nextInvoiceNumber();
        String octNum = nextInvoiceNumber();
        String novNum = nextInvoiceNumber();

        Invoice sep = importer.importDocument(createInvoiceData(testNit, sepNum, YearMonth.of(2026, 9), new BigDecimal("500000.00")));
        Invoice oct = importer.importDocument(createInvoiceData(testNit, octNum, YearMonth.of(2026, 10), new BigDecimal("1000000.00")));
        Invoice nov = importer.importDocument(createInvoiceData(testNit, novNum, YearMonth.of(2026, 11), new BigDecimal("800000.00")));

        // Pago general 1: $700.000 -> extingue Septiembre ($500.000) y abona $200.000 a Octubre
        PaymentResult res1 = paymentService.confirmPayment(new PaymentConfirmationCommand(
                testNit, nextTxId("TX-COH-01"), null, null, new BigDecimal("700000.00"),
                LocalDate.of(2026, 11, 28), "PASARELA", "BANCOLOMBIA", null, null, "Pago 1", null
        ));
        assertEquals(Money.of(new BigDecimal("1600000.00")), res1.remainingPendingDebt());

        // Pago general 2: $400.000 -> abona $400.000 adicionales a Octubre (que tenía $800.000 pendientes)
        PaymentResult res2 = paymentService.confirmPayment(new PaymentConfirmationCommand(
                testNit, nextTxId("TX-COH-02"), null, null, new BigDecimal("400000.00"),
                LocalDate.of(2026, 11, 29), "PASARELA", "BANCOLOMBIA", null, null, "Pago 2", null
        ));
        assertEquals(Money.of(new BigDecimal("1200000.00")), res2.remainingPendingDebt());

        // Consultar factura más reciente (Noviembre) a través de SP Pagos
        FacturaSpPagosDto spPagosNov = spPagosQueryService.getInvoiceForPayment(nov.getId());
        assertNotNull(spPagosNov);

        // En Noviembre:
        // Saldo propio de la factura de noviembre = $800.000
        assertEquals(new BigDecimal("800000.00"), spPagosNov.importes().totalOficial());
        assertEquals(new BigDecimal("800000.00"), spPagosNov.importes().saldoPendiente());

        // Cartera anterior pendiente (Septiembre 0 + Octubre restante 400.000) = $400.000
        assertNotNull(spPagosNov.cartera());
        assertEquals(new BigDecimal("400000.00"), spPagosNov.cartera().carteraAnteriorPendiente());
        assertEquals(1, spPagosNov.cartera().obligacionesAnteriores().size());
        assertEquals(octNum, spPagosNov.cartera().obligacionesAnteriores().getFirst().numeroFactura());
        assertEquals(new BigDecimal("400000.00"), spPagosNov.cartera().obligacionesAnteriores().getFirst().saldoPendiente());

        // Saldo consolidado total por cobrar = $800.000 (noviembre) + $400.000 (octubre) = $1.200.000
        assertEquals(new BigDecimal("1200000.00"), spPagosNov.importes().saldoTotalPorCobrar());
        assertEquals(new BigDecimal("1200000.00"), spPagosNov.cartera().saldoTotalPorCobrar());

        // Coherencia matemática:
        // Cartera inicial ($2.300.000) - Pagos aplicados ($700.000 + $400.000 = $1.100.000) = $1.200.000
        BigDecimal carteraInicial = new BigDecimal("2300000.00");
        BigDecimal pagosAplicados = new BigDecimal("1100000.00");
        assertEquals(carteraInicial.subtract(pagosAplicados), spPagosNov.importes().saldoTotalPorCobrar());
    }
}
