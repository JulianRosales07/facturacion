package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.service.PaymentService.PaymentConfirmationCommand;
import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.ClientBillingRule;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.InvoiceAvailabilityService.AvailabilityStatus;
import com.sistemaspalacios.billing.service.InvoiceAvailabilityService.ClientPeriodAvailabilityReport;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas obligatorias de integración:
 * Disponibilidad mensual de facturas y reglas especiales para clientes corporativos.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class MonthlyInvoiceAvailabilityIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(700000);

    @Autowired
    private OfficialInvoiceImportService importer;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private SpPagosQueryService spPagosQueryService;

    @Autowired
    private InvoiceAvailabilityService availabilityService;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioPago repositorioPago;

    private String testNit;
    private CorporateClient testClient;

    @BeforeEach
    void setUp() {
        testNit = "NIT-" + UUID.randomUUID().toString().substring(0, 8);
        testClient = registerClient(testNit, "CLIENTE PRUEBA " + testNit, ClientBillingRule.ORDINARIA, null);
    }

    private CorporateClient registerClient(String nit, String name, ClientBillingRule rule, String details) {
        CorporateClient client = new CorporateClient(
                UUID.randomUUID(),
                nit,
                name,
                "Calle 10 # 20-30",
                "facturacion@" + nit.toLowerCase() + ".com",
                "3001234567",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Bogota",
                null
        );
        client.setBillingRule(rule);
        client.setCorporateRuleDetails(details);
        return repositorioCliente.saveAndFlush(client);
    }

    private OfficialInvoiceData createInvoiceData(String nit, String number, YearMonth servicePeriod, BigDecimal total, LocalDate issueDate, LocalDate dueDate) {
        BigDecimal subtotal = total.divide(new BigDecimal("1.19"), 2, RoundingMode.HALF_UP);
        BigDecimal iva = total.subtract(subtotal);
        String description = "Servicio de telecomunicaciones corporativas GR 01-31 " + monthCode(servicePeriod.getMonthValue()) + " " + servicePeriod.getYear();
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

    private String nextNum() {
        return String.valueOf(SEQ.incrementAndGet());
    }

    private String monthCode(int month) {
        return switch (month) {
            case 1 -> "ENE"; case 2 -> "FEB"; case 3 -> "MAR"; case 4 -> "ABR";
            case 5 -> "MAY"; case 6 -> "JUN"; case 7 -> "JUL"; case 8 -> "AGT";
            case 9 -> "SEP"; case 10 -> "OCT"; case 11 -> "NOV"; case 12 -> "DIC";
            default -> "NOV";
        };
    }

    @Test
    @DisplayName("1. Factura de noviembre importada el 1 de noviembre y disponible para pago")
    void test01_facturaNoviembreImportadaEl1Noviembre_disponibleParaPago() {
        YearMonth nov = YearMonth.of(2026, 11);
        LocalDate importDate = LocalDate.of(2026, 11, 1);
        Instant availableAt = importDate.atStartOfDay().toInstant(ZoneOffset.UTC);

        String invNum = nextNum();
        OfficialInvoiceData data = createInvoiceData(testNit, invNum, nov, new BigDecimal("1000000.00"), importDate, nov.atEndOfMonth());
        Invoice inv = importer.importDocument(data, importDate, availableAt);

        assertNotNull(inv.getId());
        assertEquals("2026-11", periodCodeOf(inv));
        assertFalse(inv.isImportDelayRecorded(), "Importada el día 1 no debe tener retraso registrado");
        assertNotNull(inv.getAvailableForPaymentAt());

        // Evaluar disponibilidad mediante el servicio de auditoría el 1 de noviembre
        ClientPeriodAvailabilityReport report = availabilityService.evaluateClientAvailability(testNit, nov, importDate);
        assertEquals(AvailabilityStatus.DISPONIBLE_A_TIEMPO, report.status());
        assertTrue(report.availableForPayment());
        assertEquals(invNum, report.invoiceNumber());

        // Exposición en SP Pagos
        FacturaSpPagosDto spPagos = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");
        assertTrue(spPagos.pagable());
        assertEquals("2026-11", spPagos.periodo());
        assertEquals(new BigDecimal("1000000.00"), spPagos.importes().saldoPendiente());
        assertEquals(importDate, spPagos.fechas().fechaDisponiblePago());
    }

    @Test
    @DisplayName("2. Factura de noviembre importada el 2 de noviembre y disponible para pago")
    void test02_facturaNoviembreImportadaEl2Noviembre_disponibleParaPago() {
        YearMonth nov = YearMonth.of(2026, 11);
        LocalDate importDate = LocalDate.of(2026, 11, 2);
        Instant availableAt = importDate.atStartOfDay().toInstant(ZoneOffset.UTC);

        String invNum = nextNum();
        OfficialInvoiceData data = createInvoiceData(testNit, invNum, nov, new BigDecimal("1500000.00"), importDate, nov.atEndOfMonth());
        Invoice inv = importer.importDocument(data, importDate, availableAt);

        assertNotNull(inv.getId());
        assertEquals("2026-11", periodCodeOf(inv));
        assertFalse(inv.isImportDelayRecorded(), "Importada el día 2 está dentro del plazo límite ordinario");

        // Evaluar disponibilidad al 2 de noviembre
        ClientPeriodAvailabilityReport report = availabilityService.evaluateClientAvailability(testNit, nov, importDate);
        assertEquals(AvailabilityStatus.DISPONIBLE_A_TIEMPO, report.status());
        assertTrue(report.availableForPayment());

        FacturaSpPagosDto spPagos = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");
        assertTrue(spPagos.pagable());
        assertEquals(new BigDecimal("1500000.00"), spPagos.importes().saldoPendiente());
    }

    @Test
    @DisplayName("3. Factura de noviembre no importada al finalizar el día 2: se identifica la incidencia sin inventar la factura")
    void test03_facturaNoviembreNoImportadaAlFinalizarDia2_identificaIncidenciaSinInventarFactura() {
        YearMonth nov = YearMonth.of(2026, 11);
        LocalDate evalDate = LocalDate.of(2026, 11, 3); // Corte posterior al día 2

        // No se importa nada para noviembre
        ClientPeriodAvailabilityReport report = availabilityService.evaluateClientAvailability(testNit, nov, evalDate);

        assertEquals(AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE, report.status());
        assertFalse(report.availableForPayment());
        assertNull(report.invoiceNumber());
        assertTrue(report.observations().contains("INCIDENCIA"));

        // Verificar que no se inventaron facturas en la base de datos
        List<Invoice> clientInvoices = repositorioFactura.findByClientId(testClient.getId());
        assertTrue(clientInvoices.isEmpty(), "No deben existir facturas creadas ni ficticias");

        // Consultar SP Pagos debe lanzar excepción sin inventar cobros
        assertThrows(NoSuchElementException.class, () ->
                spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11"));
    }

    @Test
    @DisplayName("4. Factura de octubre pendiente y factura de noviembre importada: ambas obligaciones identificadas y cartera calculada")
    void test04_facturaOctubrePendienteYFacturaNoviembreImportada_carteraCalculada() {
        YearMonth oct = YearMonth.of(2026, 10);
        YearMonth nov = YearMonth.of(2026, 11);

        String octNum = nextNum();
        OfficialInvoiceData octData = createInvoiceData(testNit, octNum, oct, new BigDecimal("1000000.00"), oct.atDay(1), oct.atEndOfMonth());
        Invoice octInv = importer.importDocument(octData, oct.atDay(1), oct.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        String novNum = nextNum();
        OfficialInvoiceData novData = createInvoiceData(testNit, novNum, nov, new BigDecimal("1200000.00"), nov.atDay(2), nov.atEndOfMonth());
        Invoice novInv = importer.importDocument(novData, nov.atDay(2), nov.atDay(2).atStartOfDay().toInstant(ZoneOffset.UTC));

        // Consultar SP Pagos para la factura de noviembre
        FacturaSpPagosDto novSpPagos = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");
        assertEquals("2026-11", novSpPagos.periodo());
        assertEquals(new BigDecimal("1200000.00"), novSpPagos.importes().saldoPendiente());
        assertEquals(new BigDecimal("1000000.00"), novSpPagos.importes().carteraAnteriorPendiente());
        assertEquals(new BigDecimal("2200000.00"), novSpPagos.importes().saldoTotalPorCobrar());

        assertNotNull(novSpPagos.cartera());
        assertEquals(1, novSpPagos.cartera().obligacionesAnteriores().size());
        assertEquals(octNum, novSpPagos.cartera().obligacionesAnteriores().get(0).numeroFactura());
        assertEquals(new BigDecimal("1000000.00"), novSpPagos.cartera().obligacionesAnteriores().get(0).saldoPendiente());
    }

    @Test
    @DisplayName("5. Factura de octubre pagada y factura de noviembre importada: no se arrastra deuda ya saldada")
    void test05_facturaOctubrePagadaYFacturaNoviembreImportada_noArrastraDeudaSaldada() {
        YearMonth oct = YearMonth.of(2026, 10);
        YearMonth nov = YearMonth.of(2026, 11);

        String octNum = nextNum();
        OfficialInvoiceData octData = createInvoiceData(testNit, octNum, oct, new BigDecimal("1000000.00"), oct.atDay(1), oct.atEndOfMonth());
        Invoice octInv = importer.importDocument(octData, oct.atDay(1), oct.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        // Pagar octubre por completo
        PaymentConfirmationCommand payCmd = new PaymentConfirmationCommand(
                testNit,
                "TX-OCT-" + octNum,
                null,
                List.of(octInv.getId()),
                new BigDecimal("1000000.00"),
                LocalDate.of(2026, 10, 20),
                "PSE",
                "BANCO_TEST",
                null,
                null,
                "Pago total octubre",
                "IDEM-OCT-" + octNum
        );
        paymentService.confirmPayment(payCmd);

        // Importar noviembre
        String novNum = nextNum();
        OfficialInvoiceData novData = createInvoiceData(testNit, novNum, nov, new BigDecimal("1200000.00"), nov.atDay(1), nov.atEndOfMonth());
        importer.importDocument(novData, nov.atDay(1), nov.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        FacturaSpPagosDto novSpPagos = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");
        assertEquals(new BigDecimal("1200000.00"), novSpPagos.importes().saldoPendiente());
        assertEquals(new BigDecimal("0.00"), novSpPagos.importes().carteraAnteriorPendiente());
        assertEquals(new BigDecimal("1200000.00"), novSpPagos.importes().saldoTotalPorCobrar());
        assertTrue(novSpPagos.cartera().obligacionesAnteriores().isEmpty(), "Octubre pagada no debe figurar como obligación pendiente anterior");
    }

    @Test
    @DisplayName("6. Factura de octubre parcialmente pagada: solo se conserva su saldo real")
    void test06_facturaOctubreParcialmentePagada_soloConservaSaldoReal() {
        YearMonth oct = YearMonth.of(2026, 10);
        YearMonth nov = YearMonth.of(2026, 11);

        String octNum = nextNum();
        OfficialInvoiceData octData = createInvoiceData(testNit, octNum, oct, new BigDecimal("1000000.00"), oct.atDay(1), oct.atEndOfMonth());
        Invoice octInv = importer.importDocument(octData, oct.atDay(1), oct.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        // Abono parcial a octubre por $400.000 (saldo restante: $600.000)
        PaymentConfirmationCommand payCmd = new PaymentConfirmationCommand(
                testNit,
                "TX-OCT-PARTIAL-" + octNum,
                null,
                List.of(octInv.getId()),
                new BigDecimal("400000.00"),
                LocalDate.of(2026, 10, 25),
                "PSE",
                "BANCO_TEST",
                null,
                null,
                "Abono parcial octubre",
                "IDEM-OCT-PARTIAL-" + octNum
        );
        paymentService.confirmPayment(payCmd);

        // Importar noviembre
        String novNum = nextNum();
        OfficialInvoiceData novData = createInvoiceData(testNit, novNum, nov, new BigDecimal("1200000.00"), nov.atDay(2), nov.atEndOfMonth());
        importer.importDocument(novData, nov.atDay(2), nov.atDay(2).atStartOfDay().toInstant(ZoneOffset.UTC));

        FacturaSpPagosDto novSpPagos = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");
        assertEquals(new BigDecimal("1200000.00"), novSpPagos.importes().saldoPendiente());
        assertEquals(new BigDecimal("600000.00"), novSpPagos.importes().carteraAnteriorPendiente());
        assertEquals(new BigDecimal("1800000.00"), novSpPagos.importes().saldoTotalPorCobrar());

        assertEquals(1, novSpPagos.cartera().obligacionesAnteriores().size());
        assertEquals(new BigDecimal("600000.00"), novSpPagos.cartera().obligacionesAnteriores().get(0).saldoPendiente());
        assertEquals(new BigDecimal("400000.00"), novSpPagos.cartera().obligacionesAnteriores().get(0).valorPagado());
    }

    @Test
    @DisplayName("7. Reimportación del Excel sin duplicar obligaciones ni cobros")
    void test07_reimportacionExcelSinDuplicarObligacionesNiCobros() {
        YearMonth nov = YearMonth.of(2026, 11);
        String invNum = nextNum();
        OfficialInvoiceData data = createInvoiceData(testNit, invNum, nov, new BigDecimal("1000000.00"), nov.atDay(1), nov.atEndOfMonth());

        Invoice first = importer.importDocument(data, nov.atDay(1), nov.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        Invoice second = importer.importDocument(data, nov.atDay(1), nov.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        assertEquals(first.getId(), second.getId(), "La reimportación del mismo documento debe ser idempotente y retornar el mismo agregado");

        List<Invoice> allClientInvoices = repositorioFactura.findByClientId(testClient.getId());
        assertEquals(1, allClientInvoices.size(), "No debe duplicarse la fila de la factura");

        FacturaSpPagosDto spPagos = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");
        assertEquals(new BigDecimal("1000000.00"), spPagos.importes().saldoTotalPorCobrar(), "El total no debe duplicarse");
    }

    @Test
    @DisplayName("8. Factura de noviembre importada después del día 2: conserva su periodo correcto y registra retraso")
    void test08_facturaNoviembreImportadaDespuesDia2_conservaPeriodoYRegistraRetraso() {
        YearMonth nov = YearMonth.of(2026, 11);
        LocalDate lateImportDate = LocalDate.of(2026, 11, 8); // Día 8, retraso evidente
        Instant availableAt = lateImportDate.atStartOfDay().toInstant(ZoneOffset.UTC);

        String invNum = nextNum();
        OfficialInvoiceData data = createInvoiceData(testNit, invNum, nov, new BigDecimal("1300000.00"), lateImportDate, nov.atEndOfMonth());
        Invoice inv = importer.importDocument(data, lateImportDate, availableAt);

        // Periodo oficial conservado
        assertEquals("2026-11", periodCodeOf(inv), "Debe conservar el periodo de servicio oficial de noviembre sin alteración");
        assertTrue(inv.isImportDelayRecorded(), "Debe marcarse el retraso de importación");

        ClientPeriodAvailabilityReport report = availabilityService.evaluateClientAvailability(testNit, nov, lateImportDate);
        assertEquals(AvailabilityStatus.DISPONIBLE_CON_RETRASO, report.status());
        assertTrue(report.availableForPayment());
        assertTrue(report.observations().contains("retraso"));

        FacturaSpPagosDto spPagos = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");
        assertEquals("2026-11", spPagos.periodo());
        assertEquals(lateImportDate, spPagos.fechas().fechaDisponiblePago());
    }

    @Test
    @DisplayName("9. Cliente corporativo con regla especial y cliente ordinario: cada uno sigue su configuración")
    void test09_clienteCorporativoReglaEspecialYClienteOrdinario_siguenSuConfiguracion() {
        YearMonth nov = YearMonth.of(2026, 11);
        LocalDate evalDate = LocalDate.of(2026, 11, 3); // Después del corte del día 2

        // Cliente Ordinario A: sin factura al 3 de noviembre -> INCIDENCIA_FACTURA_FALTANTE
        String nitOrdinario = "NIT-ORD-" + UUID.randomUUID().toString().substring(0, 8);
        registerClient(nitOrdinario, "CLIENTE ORDINARIO", ClientBillingRule.ORDINARIA, null);

        ClientPeriodAvailabilityReport reportOrd = availabilityService.evaluateClientAvailability(nitOrdinario, nov, evalDate);
        assertEquals(AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE, reportOrd.status());
        assertFalse(reportOrd.availableForPayment());

        // Cliente Corporativo Especial B: regla especial (corte día 20 por contrato) -> REGLA_CORPORATIVA_ESPECIAL
        String nitEspecial = "NIT-CORP-" + UUID.randomUUID().toString().substring(0, 8);
        registerClient(nitEspecial, "CLIENTE CORPORATIVO GRANDE", ClientBillingRule.ESPECIAL_CORPORATIVA, "Corte contractual acordado día 20");

        ClientPeriodAvailabilityReport reportEsp = availabilityService.evaluateClientAvailability(nitEspecial, nov, evalDate);
        assertEquals(AvailabilityStatus.REGLA_CORPORATIVA_ESPECIAL, reportEsp.status());
        assertFalse(reportEsp.availableForPayment());
        assertTrue(reportEsp.observations().contains("regla contractual especial"));
        assertNotEquals(AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE, reportEsp.status(), "No debe marcarse como incidencia de corte del día 2");
    }

    @Test
    @DisplayName("10. Facturas de varios meses del mismo cliente sin colisiones por NIT")
    void test10_facturasVariosMesesMismoClienteSinColisionesPorNit() {
        YearMonth sep = YearMonth.of(2026, 9);
        YearMonth oct = YearMonth.of(2026, 10);
        YearMonth nov = YearMonth.of(2026, 11);

        String numSep = nextNum();
        String numOct = nextNum();
        String numNov = nextNum();

        importer.importDocument(createInvoiceData(testNit, numSep, sep, new BigDecimal("800000.00"), sep.atDay(1), sep.atEndOfMonth()), sep.atDay(1), sep.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        importer.importDocument(createInvoiceData(testNit, numOct, oct, new BigDecimal("900000.00"), oct.atDay(1), oct.atEndOfMonth()), oct.atDay(1), oct.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        importer.importDocument(createInvoiceData(testNit, numNov, nov, new BigDecimal("1000000.00"), nov.atDay(1), nov.atEndOfMonth()), nov.atDay(1), nov.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        List<FacturaSpPagosDto> lista = spPagosQueryService.listInvoicesForPayment(testNit, null, true);
        assertEquals(3, lista.size(), "Deben coexistir las 3 facturas sin colisiones de NIT");

        // Cada factura debe mantener su propio número, periodo y saldo independiente
        assertTrue(lista.stream().anyMatch(f -> numSep.equals(f.numeroFactura()) && "2026-09".equals(f.periodo())));
        assertTrue(lista.stream().anyMatch(f -> numOct.equals(f.numeroFactura()) && "2026-10".equals(f.periodo())));
        assertTrue(lista.stream().anyMatch(f -> numNov.equals(f.numeroFactura()) && "2026-11".equals(f.periodo())));
    }

    @Test
    @DisplayName("11. JSON de SP Pagos sin duplicar la factura actual ni la cartera anterior")
    void test11_jsonSpPagosSinDuplicarFacturaActualNiCarteraAnterior() {
        YearMonth oct = YearMonth.of(2026, 10);
        YearMonth nov = YearMonth.of(2026, 11);

        String octNum = nextNum();
        String novNum = nextNum();

        importer.importDocument(createInvoiceData(testNit, octNum, oct, new BigDecimal("1000000.00"), oct.atDay(1), oct.atEndOfMonth()), oct.atDay(1), oct.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        importer.importDocument(createInvoiceData(testNit, novNum, nov, new BigDecimal("1500000.00"), nov.atDay(1), nov.atEndOfMonth()), nov.atDay(1), nov.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        FacturaSpPagosDto novDto = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");

        // Verificaciones numéricas estrictas
        assertEquals(new BigDecimal("1500000.00"), novDto.importes().saldoPendiente(), "Saldo de la factura actual");
        assertEquals(new BigDecimal("1000000.00"), novDto.importes().carteraAnteriorPendiente(), "Saldo de obligaciones anteriores");
        assertEquals(new BigDecimal("2500000.00"), novDto.importes().saldoTotalPorCobrar(), "Saldo total por cobrar");

        // En la lista de obligaciones anteriores NO debe estar la factura actual de noviembre
        assertNotNull(novDto.cartera());
        assertEquals(1, novDto.cartera().obligacionesAnteriores().size());
        assertEquals(octNum, novDto.cartera().obligacionesAnteriores().get(0).numeroFactura());
        assertFalse(novDto.cartera().obligacionesAnteriores().stream().anyMatch(o -> novNum.equals(o.numeroFactura())),
                "La factura actual de noviembre no debe repetirse en la cartera anterior");
    }

    @Test
    @DisplayName("12. Conservación del histórico de facturas y pagos")
    void test12_conservacionHistoricoDeFacturasYPagos() {
        YearMonth oct = YearMonth.of(2026, 10);
        YearMonth nov = YearMonth.of(2026, 11);

        String octNum = nextNum();
        Invoice octInv = importer.importDocument(createInvoiceData(testNit, octNum, oct, new BigDecimal("1000000.00"), oct.atDay(1), oct.atEndOfMonth()), oct.atDay(1), oct.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        // Pagar octubre
        PaymentConfirmationCommand payCmd = new PaymentConfirmationCommand(
                testNit,
                "TRX-HIST-" + octNum,
                null,
                List.of(octInv.getId()),
                new BigDecimal("1000000.00"),
                LocalDate.of(2026, 10, 20),
                "PSE",
                "BANCO_TEST",
                null,
                null,
                "Pago histórico octubre",
                "IDEM-HIST-" + octNum
        );
        paymentService.confirmPayment(payCmd);

        // Importar noviembre
        String novNum = nextNum();
        Invoice novInv = importer.importDocument(createInvoiceData(testNit, novNum, nov, new BigDecimal("1500000.00"), nov.atDay(1), nov.atEndOfMonth()), nov.atDay(1), nov.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        // Verificar histórico en base de datos
        Invoice octDb = repositorioFactura.findById(octInv.getId()).orElseThrow();
        assertEquals(InvoiceStatus.PAID, octDb.getStatus());
        assertEquals("F1:" + octNum, octDb.getExternalDocumentKey());
        assertNotNull(octDb.getCufe());

        Invoice novDb = repositorioFactura.findById(novInv.getId()).orElseThrow();
        assertEquals(InvoiceStatus.CONSOLIDATED, novDb.getStatus());
        assertEquals("F1:" + novNum, novDb.getExternalDocumentKey());

        // Comprobar trazabilidad en repositorio de pagos
        var payments = repositorioPago.findAppliedPaymentsByInvoiceId(octInv.getId());
        assertEquals(1, payments.size());
        assertEquals("TRX-HIST-" + octNum, payments.get(0).getTransactionNumber());

        // Ambas facturas permanecen en el historial del cliente (incluso incluyendo pagadas)
        List<FacturaSpPagosDto> allInvoices = spPagosQueryService.listInvoicesForPayment(testNit, null, false);
        assertEquals(2, allInvoices.size());
    }

    private String periodCodeOf(Invoice invoice) {
        if (invoice.getPeriodId() != null) {
            var period = repositorioPeriodo.findById(invoice.getPeriodId());
            if (period.isPresent()) return period.get().getCode();
        }
        return invoice.getIssueDate() != null ? YearMonth.from(invoice.getIssueDate()).toString() : null;
    }
}
