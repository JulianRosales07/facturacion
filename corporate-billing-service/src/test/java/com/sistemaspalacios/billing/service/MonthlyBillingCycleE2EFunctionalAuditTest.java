package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.ClientBillingRule;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.IncomingFileHistory;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.InvoiceAvailabilityService.AvailabilityStatus;
import com.sistemaspalacios.billing.service.InvoiceAvailabilityService.ClientPeriodAvailabilityReport;
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
 * Auditoría funcional de extremo a extremo:
 * Ciclo mensual de facturación, límites temporales, reglas especiales corporativas
 * y conexión entre importación, disponibilidad, consulta SP Pagos y aplicación de pagos.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class MonthlyBillingCycleE2EFunctionalAuditTest {

    private static final AtomicLong SEQ = new AtomicLong(800000);

    @Autowired
    private OfficialInvoiceImportService importer;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private SpPagosQueryService spPagosQueryService;

    @Autowired
    private InvoiceAvailabilityService availabilityService;

    @Autowired
    private IncomingFileAuditService auditService;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioPago repositorioPago;

    private String testNit;
    private CorporateClient ordinaryClient;

    @BeforeEach
    void setUp() {
        testNit = "NIT-E2E-" + UUID.randomUUID().toString().substring(0, 8);
        ordinaryClient = createClient(testNit, "EMPRESA ORDINARIA " + testNit, ClientBillingRule.ORDINARIA, null);
    }

    private CorporateClient createClient(String nit, String name, ClientBillingRule rule, String details) {
        CorporateClient c = new CorporateClient(
                UUID.randomUUID(),
                nit,
                name,
                "Av Eldorado # 68-10",
                "pagos@" + nit.toLowerCase() + ".com",
                "3109998877",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Bogota",
                null
        );
        c.setBillingRule(rule);
        c.setCorporateRuleDetails(details);
        return repositorioCliente.saveAndFlush(c);
    }

    private OfficialInvoiceData createInvoiceData(String nit, String number, YearMonth servicePeriod, BigDecimal total, LocalDate issueDate, LocalDate dueDate) {
        BigDecimal subtotal = total.divide(new BigDecimal("1.19"), 2, RoundingMode.HALF_UP);
        BigDecimal iva = total.subtract(subtotal);
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
    @DisplayName("Límite temporal 1: Al corte del día 2 sin factura se reporta PENDIENTE_DENTRO_DEL_PLAZO sin crear ficticias")
    void testLimiteTemporal_finalDia2_sinFactura_pendienteDentroDelPlazo() {
        YearMonth nov = YearMonth.of(2026, 11);
        LocalDate finDia2 = LocalDate.of(2026, 11, 2);

        ClientPeriodAvailabilityReport report = availabilityService.evaluateClientAvailability(testNit, nov, finDia2);
        assertEquals(AvailabilityStatus.PENDIENTE_DENTRO_DEL_PLAZO, report.status());
        assertFalse(report.availableForPayment());
        assertNull(report.invoiceNumber());

        // Asegurar que no se crearon facturas ficticias en DB
        List<Invoice> facturas = repositorioFactura.findByClientId(ordinaryClient.getId());
        assertTrue(facturas.isEmpty(), "No deben crearse facturas ficticias");
    }

    @Test
    @DisplayName("Límite temporal 2: Al inicio del día 3 sin factura se activa INCIDENCIA_FACTURA_FALTANTE")
    void testLimiteTemporal_inicioDia3_sinFactura_incidenciaFacturaFaltante() {
        YearMonth nov = YearMonth.of(2026, 11);
        LocalDate inicioDia3 = LocalDate.of(2026, 11, 3);

        ClientPeriodAvailabilityReport report = availabilityService.evaluateClientAvailability(testNit, nov, inicioDia3);
        assertEquals(AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE, report.status());
        assertFalse(report.availableForPayment());
        assertTrue(report.observations().contains("INCIDENCIA"));

        // SP Pagos no debe inventar cobros
        assertThrows(NoSuchElementException.class, () ->
                spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11"));
    }

    @Test
    @DisplayName("Límite temporal 3: Factura importada el día 4 conserva periodo de servicio y registra retraso")
    void testLimiteTemporal_importadaDia4_conservaPeriodoYRegistraRetraso() {
        YearMonth nov = YearMonth.of(2026, 11);
        LocalDate fechaImportacion = LocalDate.of(2026, 11, 4);
        Instant availableAt = fechaImportacion.atStartOfDay().toInstant(ZoneOffset.UTC);

        String num = nextNum();
        OfficialInvoiceData data = createInvoiceData(testNit, num, nov, new BigDecimal("2000000.00"), fechaImportacion, nov.atEndOfMonth());
        Invoice inv = importer.importDocument(data, fechaImportacion, availableAt);

        // Verificación de invariantes
        assertEquals(InvoiceStatus.CONSOLIDATED, inv.getStatus());
        assertTrue(inv.isImportDelayRecorded(), "Debe marcarse el retraso de importación");
        assertEquals(availableAt, inv.getAvailableForPaymentAt(), "availableForPaymentAt debe coincidir con la fecha de validación");

        // Evaluación de disponibilidad al día 4
        ClientPeriodAvailabilityReport report = availabilityService.evaluateClientAvailability(testNit, nov, fechaImportacion);
        assertEquals(AvailabilityStatus.DISPONIBLE_CON_RETRASO, report.status());
        assertTrue(report.availableForPayment());

        // Consulta SP Pagos
        FacturaSpPagosDto spPagos = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");
        assertEquals("2026-11", spPagos.periodo(), "Periodo oficial inmutable");
        assertEquals(fechaImportacion, spPagos.fechas().fechaDisponiblePago());
    }

    @Test
    @DisplayName("Idempotencia y preservación de availableForPaymentAt en reimportaciones")
    void testReimportacionIdempotente_noSobrescribeAvailableForPaymentAt() {
        YearMonth nov = YearMonth.of(2026, 11);
        LocalDate dia1 = LocalDate.of(2026, 11, 1);
        Instant primerAvailableAt = dia1.atStartOfDay().toInstant(ZoneOffset.UTC);

        String num = nextNum();
        OfficialInvoiceData data = createInvoiceData(testNit, num, nov, new BigDecimal("1000000.00"), dia1, nov.atEndOfMonth());
        Invoice inv1 = importer.importDocument(data, dia1, primerAvailableAt);

        // Reimportar el día 10 con timestamp posterior
        LocalDate dia10 = LocalDate.of(2026, 11, 10);
        Instant segundoAvailableAt = dia10.atStartOfDay().toInstant(ZoneOffset.UTC);
        Invoice inv2 = importer.importDocument(data, dia10, segundoAvailableAt);

        assertEquals(inv1.getId(), inv2.getId());
        assertEquals(primerAvailableAt, inv2.getAvailableForPaymentAt(),
                "availableForPaymentAt no debe reiniciarse en una reimportación idempotente");
        assertFalse(inv2.isImportDelayRecorded(), "No debe marcarse como retrasada si originalmente fue a tiempo");
    }

    @Test
    @DisplayName("Protección de fechaEvaluacion: fechas manipuladas no alteran la base de datos ni inventan obligaciones")
    void testProteccionFechaEvaluacion_noAlteraPersistenciaNiInventaCobros() {
        YearMonth nov = YearMonth.of(2026, 11);

        // Evaluar con fechas arbitrarias (en el pasado o en el futuro lejano)
        ClientPeriodAvailabilityReport repFuturo = availabilityService.evaluateClientAvailability(testNit, nov, LocalDate.of(2030, 12, 31));
        assertFalse(repFuturo.availableForPayment(), "Incluso evaluando en el año 2030, sin documento real la factura no es pagable");

        ClientPeriodAvailabilityReport repPasado = availabilityService.evaluateClientAvailability(testNit, nov, LocalDate.of(2020, 1, 1));
        assertFalse(repPasado.availableForPayment());

        // Verificar que no se insertó ninguna fila en factura
        assertEquals(0, repositorioFactura.findByClientId(ordinaryClient.getId()).size());
    }

    @Test
    @DisplayName("Regla especial corporativa: no exclusión indefinida y activación de alerta tras corte contractual")
    void testReglaCorporativaEspecial_evaluacionContractualYFinDeExclusion() {
        YearMonth nov = YearMonth.of(2026, 11);
        String nitEspecial = "ESP-" + UUID.randomUUID().toString().substring(0, 8);
        // Cliente con corte contractual acordado el día 20
        CorporateClient especial = createClient(nitEspecial, "CLIENTE ESPECIAL SAS", ClientBillingRule.ESPECIAL_CORPORATIVA, "Corte contractual acordado día 20");

        // 1. Al día 2 (2 de noviembre): Dentro del plazo contractual, no se dispara falsa alerta
        ClientPeriodAvailabilityReport repDia2 = availabilityService.evaluateClientAvailability(nitEspecial, nov, LocalDate.of(2026, 11, 2));
        assertEquals(AvailabilityStatus.REGLA_CORPORATIVA_ESPECIAL, repDia2.status());
        assertFalse(repDia2.availableForPayment());

        // 2. Al día 15 (15 de noviembre): Sigue dentro del plazo contractual
        ClientPeriodAvailabilityReport repDia15 = availabilityService.evaluateClientAvailability(nitEspecial, nov, LocalDate.of(2026, 11, 15));
        assertEquals(AvailabilityStatus.REGLA_CORPORATIVA_ESPECIAL, repDia15.status());

        // 3. Al día 21 (21 de noviembre): Vencido el corte del día 20 sin factura -> INCIDENCIA_FACTURA_FALTANTE
        ClientPeriodAvailabilityReport repDia21 = availabilityService.evaluateClientAvailability(nitEspecial, nov, LocalDate.of(2026, 11, 21));
        assertEquals(AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE, repDia21.status(),
                "Al superar la fecha contractual pactada, el cliente especial NO debe quedar excluido indefinidamente de las alertas");
        assertTrue(repDia21.observations().contains("fecha límite contractual"));

        // 4. Si se importa tarde el día 25: queda disponible con retraso relativo a su corte
        String num = nextNum();
        importer.importDocument(createInvoiceData(nitEspecial, num, nov, new BigDecimal("3000000.00"), LocalDate.of(2026, 11, 25), nov.atEndOfMonth()),
                LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25).atStartOfDay().toInstant(ZoneOffset.UTC));

        ClientPeriodAvailabilityReport repDia25 = availabilityService.evaluateClientAvailability(nitEspecial, nov, LocalDate.of(2026, 11, 25));
        assertEquals(AvailabilityStatus.DISPONIBLE_CON_RETRASO, repDia25.status());
        assertTrue(repDia25.availableForPayment());
    }

    @Test
    @DisplayName("Coherencia integral de saldos entre consulta SP Pagos y aplicación de pagos FIFO")
    void testCoherenciaIntegral_consultaSpPagosYConfirmacionPagoFifo() {
        YearMonth oct = YearMonth.of(2026, 10);
        YearMonth nov = YearMonth.of(2026, 11);

        String octNum = nextNum();
        Invoice octInv = importer.importDocument(createInvoiceData(testNit, octNum, oct, new BigDecimal("1000000.00"), oct.atDay(1), oct.atEndOfMonth()),
                oct.atDay(1), oct.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        String novNum = nextNum();
        Invoice novInv = importer.importDocument(createInvoiceData(testNit, novNum, nov, new BigDecimal("1500000.00"), nov.atDay(1), nov.atEndOfMonth()),
                nov.atDay(1), nov.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        // Consulta inicial SP Pagos para noviembre
        FacturaSpPagosDto spInicial = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");
        assertEquals(new BigDecimal("1500000.00"), spInicial.importes().saldoPendiente());
        assertEquals(new BigDecimal("1000000.00"), spInicial.importes().carteraAnteriorPendiente());
        assertEquals(new BigDecimal("2500000.00"), spInicial.importes().saldoTotalPorCobrar());

        // Aplicar pago general de $1.500.000 vía FIFO (debe extinguir octubre $1M y abonar $500K a noviembre)
        PaymentResult payResult = paymentService.confirmPayment(new PaymentConfirmationCommand(
                testNit,
                "TX-FIFO-E2E-" + novNum,
                null,
                null,
                new BigDecimal("1500000.00"),
                LocalDate.of(2026, 11, 15),
                "PSE",
                "BANCOLOMBIA",
                null,
                null,
                "Abono general FIFO E2E",
                "IDEM-FIFO-E2E-" + novNum
        ));

        assertEquals(Money.ZERO, payResult.unappliedAmount());
        assertTrue(payResult.partialPayment());

        // Consulta posterior SP Pagos para noviembre: debe reflejar exactamente el nuevo saldo
        FacturaSpPagosDto spFinal = spPagosQueryService.getCurrentInvoiceForClientPeriod(testNit, "2026-11");
        assertEquals(new BigDecimal("1000000.00"), spFinal.importes().saldoPendiente(), "Noviembre queda con $1.000.000");
        assertEquals(new BigDecimal("0.00"), spFinal.importes().carteraAnteriorPendiente(), "Octubre quedó en $0");
        assertEquals(new BigDecimal("1000000.00"), spFinal.importes().saldoTotalPorCobrar(), "Saldo total coincide exactamente");
        assertTrue(spFinal.cartera().obligacionesAnteriores().isEmpty(), "Octubre pagada ya no figura en anteriores");
    }

    @Test
    @DisplayName("Observabilidad: Alerta de disponibilidad es distinguible de fallo técnico de archivo")
    void testObservabilidad_alertaDisponibilidadDiferenciadaDeFalloTecnico() {
        YearMonth nov = YearMonth.of(2026, 11);
        String nitSinDoc = "NIT-OBS-" + UUID.randomUUID().toString().substring(0, 6);
        createClient(nitSinDoc, "CLIENTE OBSERVABLE", ClientBillingRule.ORDINARIA, null);

        // 1. Alerta de negocio (factura faltante al corte del día 3)
        List<ClientPeriodAvailabilityReport> faltantes = availabilityService.findMissingInvoiceIncidents(nov, LocalDate.of(2026, 11, 3));
        assertTrue(faltantes.stream().anyMatch(f -> nitSinDoc.equals(f.nit())),
                "Debe registrarse como incidencia de negocio por falta de importación");

        // 2. Fallo técnico de importación (archivo corrupto o vacío)
        auditService.record("archivo_corrupto_siigo.xlsx", "CARPETA", "RECHAZADO", "hash123", 0, 0, "Formato corrupto de Excel");
        var historial = auditService.history(0, 10);
        assertTrue(historial.items().stream().anyMatch(h -> "RECHAZADO".equals(h.getStatus()) && "archivo_corrupto_siigo.xlsx".equals(h.getFilename())),
                "Los fallos técnicos quedan auditados independientemente en el historial de recepción");
    }
}
