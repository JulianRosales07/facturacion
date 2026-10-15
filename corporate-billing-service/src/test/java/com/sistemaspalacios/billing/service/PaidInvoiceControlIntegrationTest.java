package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class PaidInvoiceControlIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private IncomingInvoiceQueryService incomingInvoiceQueryService;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioPago repositorioPago;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    private final String TEST_NIT = "900123456";
    private CorporateClient testClient;
    private BillingPeriod testPeriod;
    private Invoice inv1; // F1:1001 -> $100.000
    private Invoice inv2; // F1:1002 -> $250.000
    private Invoice inv3; // F1:1003 -> $150.000

    @BeforeEach
    void setUp() {
        // 1. Cliente Corporativo
        testClient = repositorioCliente.findByNit(TEST_NIT).orElseGet(() ->
                repositorioCliente.saveAndFlush(new CorporateClient(
                        UUID.randomUUID(), TEST_NIT, "EMPRESA EJEMPLO S.A.S.",
                        "Calle 100 # 15-20", "facturacion@ejemplo.com", "3001234567",
                        (short) 1, (short) 1, BillingType.F1, false, null, null)));

        // 2. Periodo
        YearMonth ym = YearMonth.of(2026, 10);
        testPeriod = repositorioPeriodo.findByCode(ym.toString()).orElseGet(() ->
                repositorioPeriodo.saveAndFlush(new BillingPeriod(
                        UUID.randomUUID(), ym.toString(), BillingCutType.ADVANCE,
                        ym.atDay(1), ym.atEndOfMonth(), true)));

        // 3. Crear facturas oficiales de prueba
        inv1 = createOfficialInvoice("1001", BillingType.F1, new BigDecimal("100000.00"));
        inv2 = createOfficialInvoice("1002", BillingType.F1, new BigDecimal("250000.00"));
        inv3 = createOfficialInvoice("1003", BillingType.F1, new BigDecimal("150000.00"));
    }

    private Invoice createOfficialInvoice(String number, BillingType type, BigDecimal total) {
        Invoice inv = new Invoice(
                UUID.randomUUID(), testClient.getId(), testPeriod.getId(),
                type, testClient, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20)
        );
        inv.setInvoiceNumber(number);
        inv.setCufe("CUFE-" + number + "-ABCDEF123456");
        inv.markImportedFromSiigo("SIIGO-" + type.name() + "-" + number);
        inv.addItem(InvoiceItem.fromOfficialAmounts(
                inv.getId(), "SERV-01", "Servicio Corporativo #" + number,
                BigDecimal.ONE, com.sistemaspalacios.billing.domain.vo.Money.of(total), com.sistemaspalacios.billing.domain.vo.Money.ZERO
        ));
        inv.consolidate();
        return repositorioFactura.saveAndFlush(inv);
    }

    @Test
    @DisplayName("Regla 1 & 2: Pago parcial por factura individual oculta solo las pagadas y recalcula valor_total_servicio")
    void testPartialPaymentHidesOnlyPaidInvoicesAndRecalculatesTotal() {
        // Inicialmente: las 3 facturas deben aparecer agrupadas con total $500.000
        Optional<IncomingInvoiceQueryService.ClientInvoiceGroup> initialGroup =
                incomingInvoiceQueryService.findGroupByNit(TEST_NIT);
        assertTrue(initialGroup.isPresent(), "El NIT debe encontrarse inicialmente");
        assertEquals(3, initialGroup.get().invoiceCount(), "Deben haber 3 facturas pendientes");
        assertEquals(0, new BigDecimal("500000.00").compareTo(initialGroup.get().totalServiceValue()));

        // El cliente paga únicamente F1:1001 ($100.000) y F1:1002 ($250.000)
        PaymentService.PaymentConfirmationCommand cmd = new PaymentService.PaymentConfirmationCommand(
                TEST_NIT,
                "PAY-987654",
                List.of("F1:1001", "F1:1002"),
                List.of(),
                new BigDecimal("350000.00"),
                LocalDate.of(2026, 10, 7),
                "PSE",
                "BANCOLOMBIA",
                "AUTH-778899",
                "CUS-10203040",
                "Pago parcial facturas 1001 y 1002",
                "IDEM-PAY-987654"
        );

        PaymentService.PaymentResult result = paymentService.confirmPayment(cmd);
        assertNotNull(result);
        assertEquals(2, result.paidInvoiceIds().size());
        assertTrue(result.partialPayment(), "Debe reportar que el pago fue parcial");
        assertEquals(1, result.remainingPendingInvoicesCount(), "Queda 1 factura pendiente");

        // Verificar estados en base de datos
        Invoice reloaded1 = repositorioFactura.findById(inv1.getId()).orElseThrow();
        Invoice reloaded2 = repositorioFactura.findById(inv2.getId()).orElseThrow();
        Invoice reloaded3 = repositorioFactura.findById(inv3.getId()).orElseThrow();

        assertEquals(InvoiceStatus.PAID, reloaded1.getStatus(), "F1:1001 debe estar PAGADA");
        assertEquals(InvoiceStatus.PAID, reloaded2.getStatus(), "F1:1002 debe estar PAGADA");
        assertEquals(InvoiceStatus.CONSOLIDATED, reloaded3.getStatus(), "F1:1003 debe seguir PENDIENTE");

        // Al consultar nuevamente las facturas pendientes por NIT:
        Optional<IncomingInvoiceQueryService.ClientInvoiceGroup> pendingGroup =
                incomingInvoiceQueryService.findGroupByNit(TEST_NIT);
        assertTrue(pendingGroup.isPresent(), "El NIT sigue existiendo porque aún tiene F1:1003 pendiente");
        assertEquals(1, pendingGroup.get().invoiceCount(), "Solo debe devolver la factura pendiente");

        var remainingInvoice = pendingGroup.get().invoices().get(0);
        assertEquals("F1:1003", remainingInvoice.documentKey());
        assertEquals(0, new BigDecimal("150000.00").compareTo(remainingInvoice.serviceValue()));

        // El valor_total_servicio debe ser exactamente $150.000 (sin incluir las facturas pagadas)
        assertEquals(0, new BigDecimal("150000.00").compareTo(pendingGroup.get().totalServiceValue()),
                "El total debe sumar únicamente las facturas pendientes");
    }

    @Test
    @DisplayName("Regla 3 & 5: Cuando se pagan todas las facturas, el NIT desaparece completamente del listado pendiente")
    void testFullPaymentRemovesNitCompletelyFromPending() {
        // Pagar las 3 facturas en una sola transacción
        PaymentService.PaymentConfirmationCommand cmd = new PaymentService.PaymentConfirmationCommand(
                TEST_NIT,
                "PAY-FULL-100",
                List.of("F1:1001", "F1:1002", "F1:1003"),
                List.of(),
                new BigDecimal("500000.00"),
                LocalDate.of(2026, 10, 7),
                "CARD",
                "VISA",
                "AUTH-112233",
                "CUS-99887766",
                "Pago total de la cartera",
                "IDEM-FULL-100"
        );

        PaymentService.PaymentResult result = paymentService.confirmPayment(cmd);
        assertEquals(3, result.paidInvoiceIds().size());
        assertFalse(result.partialPayment(), "Ya no debe ser pago parcial, está al día");
        assertEquals(0, result.remainingPendingInvoicesCount(), "No quedan facturas pendientes");

        // Al consultar por NIT: debe retornar Optional.empty() (desaparece de pendientes)
        Optional<IncomingInvoiceQueryService.ClientInvoiceGroup> group =
                incomingInvoiceQueryService.findGroupByNit(TEST_NIT);
        assertTrue(group.isEmpty(), "Un NIT con todas sus facturas pagadas NO debe aparecer como pendiente");

        // En la búsqueda paginada general: el NIT no debe figurar en los items
        var page = incomingInvoiceQueryService.searchGroupedByNit(TEST_NIT, null, null, null, null, null, null, 0, 10);
        assertEquals(0, page.items().size(), "El listado de pendientes no debe contener al NIT pagado");
    }

    @Test
    @DisplayName("Regla 9 & Historial: Las facturas pagadas conservan trazabilidad completa en el historial")
    void testInvoicePaymentHistoryPreservesAuditTrace() {
        // Pagar F1:1001
        PaymentService.PaymentConfirmationCommand cmd = new PaymentService.PaymentConfirmationCommand(
                TEST_NIT,
                "PAY-HIST-1",
                List.of("F1:1001"),
                List.of(),
                new BigDecimal("100000.00"),
                LocalDate.of(2026, 10, 7),
                "PSE",
                "DAVIVIENDA",
                "AUTH-999",
                "CUS-4444",
                "Pago F1:1001",
                "IDEM-HIST-1"
        );
        paymentService.confirmPayment(cmd);

        // Consultar historial
        List<IncomingInvoiceQueryService.InvoiceHistoryItem> history =
                incomingInvoiceQueryService.getInvoiceHistoryByNit(TEST_NIT);

        assertEquals(3, history.size(), "El historial debe contener todas las facturas históricas");

        var item1001 = history.stream().filter(h -> h.consecutivo().equals("1001")).findFirst().orElseThrow();
        assertEquals("PAGADA", item1001.estado());
        assertEquals("PAY-HIST-1", item1001.transactionId());
        assertEquals("PSE", item1001.proveedorMedioPago());
        assertEquals(LocalDate.of(2026, 10, 7), item1001.fechaPago());
        assertEquals(0, new BigDecimal("100000.00").compareTo(item1001.valor()));

        var item1002 = history.stream().filter(h -> h.consecutivo().equals("1002")).findFirst().orElseThrow();
        assertEquals("PENDIENTE", item1002.estado());
        assertNull(item1002.fechaPago());
        assertNull(item1002.transactionId());
    }

    @Test
    @DisplayName("Regla 10: Idempotencia estricta: reintentar la misma confirmación no duplica pagos ni cruces")
    void testIdempotencyPreventsDuplicateProcessing() {
        PaymentService.PaymentConfirmationCommand cmd = new PaymentService.PaymentConfirmationCommand(
                TEST_NIT,
                "PAY-IDEMPOTENT-XYZ",
                List.of("F1:1001"),
                List.of(),
                new BigDecimal("100000.00"),
                LocalDate.of(2026, 10, 7),
                "PSE",
                "BANCOLOMBIA",
                "AUTH-1",
                "CUS-1",
                "Intento 1",
                "KEY-IDEMPOTENT"
        );

        long pagosBefore = repositorioPago.count();

        // Primer envío
        PaymentService.PaymentResult first = paymentService.confirmPayment(cmd);
        assertNotNull(first);
        assertEquals(pagosBefore + 1, repositorioPago.count());

        // Segundo envío (mismo transactionId / idempotencyKey)
        PaymentService.PaymentResult second = paymentService.confirmPayment(cmd);
        assertNotNull(second);

        // Debe devolver el mismo paymentId y NO crear un registro duplicado en base de datos
        assertEquals(first.paymentId(), second.paymentId(), "Debe retornar el mismo ID de pago registrado");
        assertEquals(pagosBefore + 1, repositorioPago.count(), "No se debe duplicar el registro en la tabla pago");
    }

    @Test
    @DisplayName("Seguridad: Intentar pagar una factura ya pagada con otra transacción debe lanzar conflicto")
    void testPayingAlreadyPaidInvoiceWithNewTransactionThrowsConflict() {
        // Pago exitoso 1
        paymentService.confirmPayment(new PaymentService.PaymentConfirmationCommand(
                TEST_NIT, "PAY-TX-FIRST", List.of("F1:1001"), new BigDecimal("100000.00")
        ));

        // Intento de volver a pagar F1:1001 con una NUEVA transacción diferente
        PaymentService.PaymentConfirmationCommand secondCmd = new PaymentService.PaymentConfirmationCommand(
                TEST_NIT, "PAY-TX-SECOND", List.of("F1:1001"), new BigDecimal("100000.00")
        );

        assertThrows(PaymentConflictException.class, () -> paymentService.confirmPayment(secondCmd),
                "Debe rechazar pagar una factura que ya tiene estado PAGADA");
    }
}
