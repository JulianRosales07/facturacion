package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingIncident;
import com.sistemaspalacios.billing.domain.model.BillingIncidentStatus;
import com.sistemaspalacios.billing.domain.model.BillingIncidentType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.ClientBillingRule;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioIncidenciaFacturacion;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.PaymentService.PaymentConfirmationCommand;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class CorporateBillingRulesAndIncidentsIntegrationTest {

    @Autowired
    private InvoiceAvailabilityService availabilityService;

    @Autowired
    private BillingIncidentService incidentService;

    @Autowired
    private OfficialInvoiceImportService importer;

    @Autowired
    private SpPagosQueryService spPagosQueryService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioIncidenciaFacturacion repositorioIncidencia;

    private static final AtomicInteger seq = new AtomicInteger(7000);

    @BeforeEach
    void setUpPeriods() {
        for (int m = 9; m <= 12; m++) {
            YearMonth ym = YearMonth.of(2026, m);
            String code = ym.toString();
            if (repositorioPeriodo.findByCode(code).isEmpty()) {
                repositorioPeriodo.saveAndFlush(new BillingPeriod(
                        UUID.randomUUID(), code, BillingCutType.ADVANCE,
                        ym.atDay(1), ym.atEndOfMonth(), true
                ));
            }
        }
    }

    private CorporateClient createClient(String nit, String name, ClientBillingRule rule, Integer cutoffDay, String details) {
        CorporateClient client = new CorporateClient(
                UUID.randomUUID(),
                nit,
                name,
                "Av. Empresarial 100",
                "billing@" + nit.toLowerCase().replace("-", "") + ".com",
                "3110000000",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Bogota",
                null
        );
        client.setBillingRule(rule);
        if (cutoffDay != null) {
            client.setCorporateCutoffDay(cutoffDay);
        }
        client.setCorporateRuleDetails(details);
        return repositorioCliente.saveAndFlush(client);
    }

    private OfficialInvoiceData createInvoiceData(String nit, String number, YearMonth servicePeriod, BigDecimal total, LocalDate issueDate, LocalDate dueDate) {
        BigDecimal subtotal = total.divide(new BigDecimal("1.19"), 2, RoundingMode.HALF_UP);
        BigDecimal iva = total.subtract(subtotal);
        String description = "Servicios corporativos GR 01-31 " + monthCode(servicePeriod.getMonthValue()) + " " + servicePeriod.getYear();
        return new OfficialInvoiceData(
                nit,
                "CLIENTE CORP " + nit,
                BillingType.F1,
                number,
                issueDate,
                dueDate,
                "CUFE-" + number + "-" + servicePeriod,
                Money.of(total),
                List.of(new OfficialInvoiceData.Item("SERV-01", description, BigDecimal.ONE, Money.of(subtotal), Money.of(iva)))
        );
    }

    private String monthCode(int month) {
        return switch (month) {
            case 1 -> "ENE"; case 2 -> "FEB"; case 3 -> "MAR"; case 4 -> "ABR";
            case 5 -> "MAY"; case 6 -> "JUN"; case 7 -> "JUL"; case 8 -> "AGT";
            case 9 -> "SEP"; case 10 -> "OCT"; case 11 -> "NOV"; case 12 -> "DIC";
            default -> throw new IllegalArgumentException("Mes invalido: " + month);
        };
    }

    private String nextNum() {
        return String.valueOf(seq.incrementAndGet());
    }

    @Test
    @DisplayName("1. Cliente corporativo con corte configurado: respeta ciclo contractual")
    void test01_clienteCorporativoConCorteConfigurado() {
        YearMonth nov = YearMonth.of(2026, 11);
        String nit = "CORP-01-" + UUID.randomUUID().toString().substring(0, 6);
        createClient(nit, "EMPRESA CORTE 20", ClientBillingRule.ESPECIAL_CORPORATIVA, 20, "Corte contractual día 20");

        // Día 5: Dentro del plazo
        var repDia5 = availabilityService.evaluateClientAvailability(nit, nov, LocalDate.of(2026, 11, 5));
        assertEquals(InvoiceAvailabilityService.AvailabilityStatus.REGLA_CORPORATIVA_ESPECIAL, repDia5.status());
        assertEquals(20, repDia5.corporateCutoffDay());
        assertFalse(repDia5.availableForPayment());

        // Día 21: Vencido el corte del día 20 -> INCIDENCIA_FACTURA_FALTANTE
        var repDia21 = availabilityService.evaluateClientAvailability(nit, nov, LocalDate.of(2026, 11, 21));
        assertEquals(InvoiceAvailabilityService.AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE, repDia21.status());
        assertTrue(repDia21.observations().contains("fecha límite contractual"));
    }

    @Test
    @DisplayName("2. Cliente corporativo sin corte configurado: informa CONFIGURACION_INCOMPLETA")
    void test02_clienteCorporativoSinCorteConfigurado() {
        YearMonth nov = YearMonth.of(2026, 11);
        String nit = "CORP-02-" + UUID.randomUUID().toString().substring(0, 6);
        CorporateClient client = createClient(nit, "EMPRESA SIN CORTE", ClientBillingRule.ESPECIAL_CORPORATIVA, null, null);

        assertFalse(client.isConfigurationComplete(), "Cliente especial sin corte debe marcar configuración incompleta");

        var rep = availabilityService.evaluateClientAvailability(nit, nov, LocalDate.of(2026, 11, 10));
        assertEquals(InvoiceAvailabilityService.AvailabilityStatus.CONFIGURACION_INCOMPLETA, rep.status());
        assertFalse(rep.availableForPayment());
        assertTrue(rep.observations().contains("sin día de corte contractual válido"));

        // Al ejecutar detección de incidencias, genera una incidencia de CONFIGURACION_INCOMPLETA
        var incidencias = incidentService.detectAndRegisterIncidents(nov, LocalDate.of(2026, 11, 10));
        assertTrue(incidencias.stream().anyMatch(i -> i.getClientId().equals(client.getId())
                && i.getIncidentType() == BillingIncidentType.CONFIGURACION_INCOMPLETA));
    }

    @Test
    @DisplayName("3. Rechazo de cortes fuera del rango permitido (1 a 28)")
    void test03_rechazoDeCortesFueraDelRangoPermitido() {
        String nit = "CORP-03-" + UUID.randomUUID().toString().substring(0, 6);
        CorporateClient client = createClient(nit, "EMPRESA RANGO", ClientBillingRule.ESPECIAL_CORPORATIVA, 15, "Corte válido");

        // Rechaza 0 y negativos
        assertThrows(IllegalArgumentException.class, () -> client.setCorporateCutoffDay(0));
        assertThrows(IllegalArgumentException.class, () -> client.setCorporateCutoffDay(-5));

        // Rechaza mayores a 28 (evita problemas con febrero)
        assertThrows(IllegalArgumentException.class, () -> client.setCorporateCutoffDay(29));
        assertThrows(IllegalArgumentException.class, () -> client.setCorporateCutoffDay(31));

        // Acepta los extremos válidos
        assertDoesNotThrow(() -> client.setCorporateCutoffDay(1));
        assertEquals(1, client.getCorporateCutoffDay());

        assertDoesNotThrow(() -> client.setCorporateCutoffDay(28));
        assertEquals(28, client.getCorporateCutoffDay());
    }

    @Test
    @DisplayName("4. Cliente ordinario con corte del día 2")
    void test04_clienteOrdinarioConCorteDelDia2() {
        YearMonth nov = YearMonth.of(2026, 11);
        String nit = "ORD-04-" + UUID.randomUUID().toString().substring(0, 6);
        createClient(nit, "CLIENTE ORDINARIO", ClientBillingRule.ORDINARIA, null, null);

        // Día 2: Pendiente dentro del plazo ordinario
        var repDia2 = availabilityService.evaluateClientAvailability(nit, nov, LocalDate.of(2026, 11, 2));
        assertEquals(InvoiceAvailabilityService.AvailabilityStatus.PENDIENTE_DENTRO_DEL_PLAZO, repDia2.status());
        assertEquals(2, repDia2.corporateCutoffDay());

        // Día 3: Incidencia de factura faltante
        var repDia3 = availabilityService.evaluateClientAvailability(nit, nov, LocalDate.of(2026, 11, 3));
        assertEquals(InvoiceAvailabilityService.AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE, repDia3.status());
    }

    @Test
    @DisplayName("5. Factura faltante detectada automáticamente al vencer el corte")
    void test05_facturaFaltanteDetectadaAutomaticamenteAlVencerCorte() {
        YearMonth nov = YearMonth.of(2026, 11);
        String nit = "ORD-05-" + UUID.randomUUID().toString().substring(0, 6);
        CorporateClient client = createClient(nit, "CLIENTE ALERTA", ClientBillingRule.ORDINARIA, null, null);

        // Al día 3 de noviembre sin factura
        List<BillingIncident> incidencias = incidentService.detectAndRegisterIncidents(nov, LocalDate.of(2026, 11, 3));

        var incOpt = incidencias.stream()
                .filter(i -> i.getClientId().equals(client.getId()) && i.getIncidentType() == BillingIncidentType.FACTURA_FALTANTE)
                .findFirst();

        assertTrue(incOpt.isPresent(), "Debe haberse registrado la incidencia de factura faltante");
        BillingIncident inc = incOpt.get();
        assertEquals(BillingIncidentStatus.ABIERTA, inc.getStatus());
        assertEquals(2, inc.getExpectedCutoffDay());
        assertEquals(LocalDate.of(2026, 11, 2), inc.getExpectedCutoffDate());
        assertNotNull(inc.getDetectedAt());
    }

    @Test
    @DisplayName("6. Ausencia de incidencias duplicadas al ejecutar repetidamente la detección")
    void test06_ausenciaDeIncidenciasDuplicadasAlEjecutarRepetidamente() {
        YearMonth nov = YearMonth.of(2026, 11);
        String nit = "ORD-06-" + UUID.randomUUID().toString().substring(0, 6);
        CorporateClient client = createClient(nit, "CLIENTE SIN DUPLICADOS", ClientBillingRule.ORDINARIA, null, null);

        // Ejecutar detección 3 veces seguidas
        incidentService.detectAndRegisterIncidents(nov, LocalDate.of(2026, 11, 3));
        incidentService.detectAndRegisterIncidents(nov, LocalDate.of(2026, 11, 4));
        incidentService.detectAndRegisterIncidents(nov, LocalDate.of(2026, 11, 5));

        List<BillingIncident> incidencias = repositorioIncidencia.findByClientId(client.getId());
        long missingCount = incidencias.stream()
                .filter(i -> i.getPeriod().equals("2026-11") && i.getIncidentType() == BillingIncidentType.FACTURA_FALTANTE)
                .count();

        assertEquals(1, missingCount, "No debe crearse más de una incidencia para el mismo cliente y periodo");
    }

    @Test
    @DisplayName("7. Resolución de una incidencia cuando llega la factura oficial")
    void test07_resolucionDeIncidenciaCuandoLlegaFacturaOficial() {
        YearMonth nov = YearMonth.of(2026, 11);
        String nit = "ORD-07-" + UUID.randomUUID().toString().substring(0, 6);
        CorporateClient client = createClient(nit, "CLIENTE RESOLUCION", ClientBillingRule.ORDINARIA, null, null);

        // Generar la incidencia de factura faltante al día 3
        incidentService.detectAndRegisterIncidents(nov, LocalDate.of(2026, 11, 3));

        var incAntes = repositorioIncidencia.findByClientIdAndPeriodAndIncidentType(
                client.getId(), "2026-11", BillingIncidentType.FACTURA_FALTANTE
        ).orElseThrow();
        assertEquals(BillingIncidentStatus.ABIERTA, incAntes.getStatus());
        assertNull(incAntes.getResolvedAt());

        // El día 5 finalmente se importa la factura oficial de noviembre
        String num = nextNum();
        importer.importDocument(
                createInvoiceData(nit, num, nov, new BigDecimal("1200000.00"), LocalDate.of(2026, 11, 5), nov.atEndOfMonth()),
                LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 11, 5).atStartOfDay().toInstant(ZoneOffset.UTC)
        );

        // Verificar que la incidencia se resolvió automáticamente
        var incDespues = repositorioIncidencia.findByClientIdAndPeriodAndIncidentType(
                client.getId(), "2026-11", BillingIncidentType.FACTURA_FALTANTE
        ).orElseThrow();

        assertEquals(BillingIncidentStatus.RESUELTA, incDespues.getStatus(), "La incidencia debe resolverse al importar la factura oficial");
        assertNotNull(incDespues.getResolvedAt(), "Debe quedar registrada la fecha de resolución");
        assertTrue(incDespues.getResolutionObservations().contains(num), "Debe incluir el número de factura en las observaciones");
    }

    @Test
    @DisplayName("8. Persistencia de la incidencia tras reiniciar el servicio (verificación en base de datos)")
    void test08_persistenciaDeIncidenciaEnBaseDeDatos() {
        YearMonth nov = YearMonth.of(2026, 11);
        String nit = "ORD-08-" + UUID.randomUUID().toString().substring(0, 6);
        CorporateClient client = createClient(nit, "CLIENTE PERSISTENCIA", ClientBillingRule.ORDINARIA, null, null);

        incidentService.detectAndRegisterIncidents(nov, LocalDate.of(2026, 11, 3));

        var incOpt = repositorioIncidencia.findByClientIdAndPeriodAndIncidentType(
                client.getId(), "2026-11", BillingIncidentType.FACTURA_FALTANTE
        );
        assertTrue(incOpt.isPresent());
        UUID id = incOpt.get().getId();

        // Comprobación directa contra la base de datos simulando nueva consulta desacoplada
        var recargada = repositorioIncidencia.findById(id).orElseThrow();
        assertEquals(client.getId(), recargada.getClientId());
        assertEquals(nit, recargada.getNit());
        assertEquals("2026-11", recargada.getPeriod());
        assertEquals(BillingIncidentType.FACTURA_FALTANTE, recargada.getIncidentType());
        assertEquals(BillingIncidentStatus.ABIERTA, recargada.getStatus());
    }

    @Test
    @DisplayName("9. Cliente corporativo con corte distinto del día 2 (ej. día 10)")
    void test09_clienteCorporativoConCorteDistintoDelDia2() {
        YearMonth nov = YearMonth.of(2026, 11);
        String nit = "CORP-09-" + UUID.randomUUID().toString().substring(0, 6);
        createClient(nit, "EMPRESA CORTE 10", ClientBillingRule.ESPECIAL_CORPORATIVA, 10, "Corte contractual día 10");

        // Día 3: No es incidencia para este cliente (su corte es el 10, no el 2)
        var repDia3 = availabilityService.evaluateClientAvailability(nit, nov, LocalDate.of(2026, 11, 3));
        assertEquals(InvoiceAvailabilityService.AvailabilityStatus.REGLA_CORPORATIVA_ESPECIAL, repDia3.status());

        // Día 11: Se activa la incidencia tras vencer el día 10
        var repDia11 = availabilityService.evaluateClientAvailability(nit, nov, LocalDate.of(2026, 11, 11));
        assertEquals(InvoiceAvailabilityService.AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE, repDia11.status());
        assertEquals(10, repDia11.corporateCutoffDay());
    }

    @Test
    @DisplayName("10. Conservación de todas las reglas previas de cartera, pagos e importación")
    void test10_conservacionDeTodasLasReglasPrevias() {
        YearMonth oct = YearMonth.of(2026, 10);
        YearMonth nov = YearMonth.of(2026, 11);
        String nit = "CLI-10-" + UUID.randomUUID().toString().substring(0, 6);
        createClient(nit, "CLIENTE INTEGRAL", ClientBillingRule.ORDINARIA, null, null);

        // 1. Factura de octubre por $1.000.000 importada en octubre
        String numOct = nextNum();
        importer.importDocument(
                createInvoiceData(nit, numOct, oct, new BigDecimal("1000000.00"), LocalDate.of(2026, 10, 1), oct.atEndOfMonth()),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        );

        // 2. Factura de noviembre por $1.500.000 importada el 1 de noviembre
        String numNov = nextNum();
        importer.importDocument(
                createInvoiceData(nit, numNov, nov, new BigDecimal("1500000.00"), LocalDate.of(2026, 11, 1), nov.atEndOfMonth()),
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        );

        // 3. Consulta SP Pagos para noviembre: factura actual por $1.500.000, cartera anterior por $1.000.000
        FacturaSpPagosDto spPagos = spPagosQueryService.getCurrentInvoiceForClientPeriod(nit, "2026-11");
        assertEquals(new BigDecimal("1500000.00"), spPagos.importes().totalOficial());
        assertEquals(new BigDecimal("1500000.00"), spPagos.importes().saldoPendiente());
        assertEquals(new BigDecimal("1000000.00"), spPagos.importes().carteraAnteriorPendiente());
        assertEquals(new BigDecimal("2500000.00"), spPagos.importes().saldoTotalPorCobrar());

        // 4. Pago FIFO de $1.200.000: cubre $1.000.000 de octubre y deja $200.000 a noviembre
        paymentService.confirmPayment(new PaymentConfirmationCommand(
                nit,
                "TRX-10-" + System.currentTimeMillis(),
                null,
                null,
                new BigDecimal("1200000.00"),
                LocalDate.of(2026, 11, 15),
                "PSE",
                "BANCOLOMBIA",
                null,
                null,
                "Recaudo general",
                "IDEM-10-" + System.currentTimeMillis()
        ));

        // 5. Saldo verificado: octubre queda saldada ($0), noviembre queda con saldo $1.300.000
        Invoice invOct = repositorioFactura.findByInvoiceNumber(numOct).orElseThrow();
        Invoice invNov = repositorioFactura.findByInvoiceNumber(numNov).orElseThrow();

        assertEquals(new BigDecimal("0.00"), invOct.getPendingBalance().amount());
        assertEquals(new BigDecimal("1300000.00"), invNov.getPendingBalance().amount());

        // Reimportación idempotente de noviembre no altera saldos ni duplica pagos
        importer.importDocument(
                createInvoiceData(nit, numNov, nov, new BigDecimal("1500000.00"), LocalDate.of(2026, 11, 1), nov.atEndOfMonth()),
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        );

        Invoice invNovReimp = repositorioFactura.findByInvoiceNumber(numNov).orElseThrow();
        assertEquals(new BigDecimal("1300000.00"), invNovReimp.getPendingBalance().amount(),
                "La reimportación idempotente debe preservar el saldo exacto resultante del pago");
    }
}
