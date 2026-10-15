package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioNovedad;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.NovedadesFacturaEnPreparacionTestSupport.Fila;
import com.sistemaspalacios.billing.service.dto.ImportResultDto;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Novedades acumuladas en la factura en preparación del cliente y periodo.
 * <p>
 * Recorre el flujo real: Excel de órdenes → {@link OrderImportService#importFile} → factura.
 * Sede de referencia: $500.000/mes en noviembre de 2026 (30 días), mes comercial de 30 días.
 * Cada prueba revierte su transacción.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class NovedadesFacturaEnPreparacionIntegrationTest {

    private static final String NOV = "2026-11";
    private static final String DIC = "2026-12";

    @Autowired private OrderImportService importService;
    @Autowired private BillingService billingService;
    @Autowired private RepositorioFactura repositorioFactura;
    @Autowired private RepositorioCliente repositorioCliente;
    @Autowired private RepositorioPeriodo repositorioPeriodo;
    @Autowired private RepositorioNovedad repositorioNovedad;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    // ── utilidades ──────────────────────────────────────────────────────────────

    private ImportResultDto importar(String periodo, Fila... filas) throws Exception {
        return importService.importFile("prueba.xlsx", NovedadesFacturaEnPreparacionTestSupport.excel(List.of(filas)),
                periodo, OrderOrigin.SPE, "EXCEL");
    }

    private Invoice generar(String periodo, String nit) {
        return billingService.generateConsolidatedInvoice(periodo, BillingType.F1, BillingCutType.ADVANCE, nit);
    }

    /** Relee la factura vigente desde la base, no desde la caché de JPA. */
    private Invoice vigente(String nit, String periodo) {
        entityManager.flush();
        entityManager.clear();
        UUID clientId = repositorioCliente.findByNit(nit).orElseThrow().getId();
        UUID periodId = repositorioPeriodo.findByCode(periodo).orElseThrow().getId();
        return repositorioFactura.findCurrentByClientAndPeriod(clientId, periodId).orElseThrow();
    }

    private int facturasDelCliente(String nit) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM factura f JOIN cliente_corporativo c ON c.id = f.cliente_corporativo_id WHERE c.nit = ?",
                Integer.class, nit);
    }

    private List<InvoiceItem> itemsOrdenados(Invoice invoice) {
        return invoice.getItems().stream().sorted(Comparator.comparing(InvoiceItem::getDescription)).toList();
    }

    private List<OrderUpdate> novedadesDe(String nit, String periodo) {
        entityManager.flush();
        UUID clientId = repositorioCliente.findByNit(nit).orElseThrow().getId();
        UUID periodId = repositorioPeriodo.findByCode(periodo).orElseThrow().getId();
        return repositorioNovedad.findByPeriodId(periodId).stream()
                .filter(u -> jdbc.queryForObject("SELECT cliente_corporativo_id FROM sede_suscripcion WHERE id = ?",
                        UUID.class, u.getSiteId()).equals(clientId))
                .toList();
    }

    private static Fila cambioPlan(String nit, String orden, int dia, long valor) {
        return Fila.novedad(nit, orden, "CAMBIO_PLAN", LocalDate.of(2026, 11, dia), valor);
    }

    // ── escenarios ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. Primera facturación: cliente sin factura → se crea una con el servicio original")
    void primeraFacturacion() throws Exception {
        String nit = "907700001";
        ImportResultDto imp = importar(NOV, Fila.sede(nit, "ACU-01-A", 500_000));
        assertTrue(imp.messages().isEmpty(), "Sin novedades no se toca ninguna factura: " + imp.messages());

        Invoice factura = generar(NOV, nit);

        Invoice leida = vigente(nit, NOV);
        assertEquals(factura.getId(), leida.getId());
        assertEquals(1, leida.getItems().size());
        assertTrue(leida.getItems().get(0).getDescription().contains("GR 01-30 NOV 2026"));
        assertNull(leida.getItems().get(0).getSourceUpdateId(), "El servicio original no viene de una novedad");
        assertEquals(Money.of(595_000), leida.getTotalAmount());
        assertEquals(1, facturasDelCliente(nit));
    }

    @Test
    @DisplayName("2. Primera novedad: se incorpora a la misma factura (mismo id y número), sin crear otra")
    void primeraNovedadSobreFacturaEnPreparacion() throws Exception {
        String nit = "907700002";
        importar(NOV, Fila.sede(nit, "ACU-02-A", 500_000));
        Invoice original = generar(NOV, nit);
        String numero = original.getInvoiceNumber();

        ImportResultDto imp = importar(NOV, cambioPlan(nit, "ACU-02-A", 16, 800_000));

        assertTrue(imp.messages().stream().anyMatch(m -> m.startsWith("Factura " + numero + " actualizada")),
                "Mensajes: " + imp.messages());
        Invoice actual = vigente(nit, NOV);
        assertEquals(original.getId(), actual.getId(), "Debe ser la misma factura, no una versión nueva");
        assertEquals(numero, actual.getInvoiceNumber(), "No debe consumir otro consecutivo");
        assertEquals(1, actual.getVersionNumber());
        assertEquals(1, facturasDelCliente(nit), "No se crean facturas adicionales ni versiones reemplazadas");

        List<InvoiceItem> items = itemsOrdenados(actual);
        assertEquals(2, items.size());
        // Cargo fijo: el plan del día 1 por el mes completo, sin tocar.
        assertTrue(items.get(0).getDescription().contains("GR 01-30 NOV 2026"));
        assertEquals(Money.of(500_000), items.get(0).getTaxableBase());
        assertNull(items.get(0).getSourceUpdateId());
        // Otros conceptos: el incremento de la novedad desde su fecha.
        assertTrue(items.get(1).getDescription().contains("INCREMENTO POR NOVEDAD GR 16-30 NOV 2026"));
        assertEquals(Money.of(150_000), items.get(1).getTaxableBase());

        OrderUpdate novedad = novedadesDe(nit, NOV).get(0);
        assertEquals(novedad.getId(), items.get(1).getSourceUpdateId(), "El incremento referencia su novedad");
        assertEquals(actual.getId(), novedad.getInvoiceId(), "La novedad queda ligada a la factura");

        assertEquals(Money.of(650_000), actual.getSubtotalAmount());
        assertEquals(Money.of(773_500), actual.getTotalAmount());
    }

    @Test
    @DisplayName("3, 4 y 10. Segunda novedad con otro importe: tres tramos en la misma factura y total recalculado")
    void segundaNovedadSobreLaMismaFactura() throws Exception {
        String nit = "907700003";
        importar(NOV, Fila.sede(nit, "ACU-03-A", 500_000));
        Invoice original = generar(NOV, nit);
        importar(NOV, cambioPlan(nit, "ACU-03-A", 16, 800_000));

        importar(NOV, cambioPlan(nit, "ACU-03-A", 21, 1_100_000));

        Invoice actual = vigente(nit, NOV);
        assertEquals(original.getId(), actual.getId());
        assertEquals(1, facturasDelCliente(nit));
        assertEquals(2, novedadesDe(nit, NOV).size(), "Dos cambios en fechas distintas son dos novedades");

        List<InvoiceItem> items = itemsOrdenados(actual);
        assertEquals(3, items.size());
        assertTrue(items.get(0).getDescription().contains("GR 01-30 NOV 2026"));
        assertEquals(Money.of(500_000), items.get(0).getTaxableBase());   // cargo fijo
        assertTrue(items.get(1).getDescription().contains("INCREMENTO POR NOVEDAD GR 16-30 NOV 2026"));
        assertEquals(Money.of(150_000), items.get(1).getTaxableBase());   // (800.000 − 500.000) × 15/30
        assertTrue(items.get(2).getDescription().contains("INCREMENTO POR NOVEDAD GR 21-30 NOV 2026"));
        assertEquals(Money.of(100_000), items.get(2).getTaxableBase());   // (1.100.000 − 800.000) × 10/30

        // 10. El total sale de los conceptos persistidos, no de sumar sobre el total anterior.
        Money subtotal = items.stream().map(InvoiceItem::getTaxableBase).reduce(Money.ZERO, Money::plus);
        Money iva = items.stream().map(InvoiceItem::getIvaValue).reduce(Money.ZERO, Money::plus);
        assertEquals(subtotal, actual.getSubtotalAmount());
        assertEquals(iva, actual.getIvaAmount());
        assertEquals(subtotal.plus(iva), actual.getTotalAmount());
        assertEquals(Money.of(750_000), actual.getSubtotalAmount());
        assertEquals(Money.of(892_500), actual.getTotalAmount());
    }

    @Test
    @DisplayName("4. Dos novedades con importes distintos en el mismo archivo")
    void dosNovedadesEnElMismoArchivo() throws Exception {
        String nit = "907700004";
        importar(NOV, Fila.sede(nit, "ACU-04-A", 500_000));
        generar(NOV, nit);

        importar(NOV, cambioPlan(nit, "ACU-04-A", 16, 800_000), cambioPlan(nit, "ACU-04-A", 21, 1_100_000));

        Invoice actual = vigente(nit, NOV);
        assertEquals(3, actual.getItems().size());
        assertEquals(Money.of(750_000), actual.getSubtotalAmount());
        assertEquals(1, facturasDelCliente(nit));
    }

    @Test
    @DisplayName("5. Reprocesar la misma novedad no crea conceptos ni duplica el total")
    void reprocesarLaMismaNovedad() throws Exception {
        String nit = "907700005";
        importar(NOV, Fila.sede(nit, "ACU-05-A", 500_000));
        generar(NOV, nit);
        importar(NOV, cambioPlan(nit, "ACU-05-A", 16, 800_000));
        UUID novedadId = novedadesDe(nit, NOV).get(0).getId();
        Invoice antes = vigente(nit, NOV);

        importar(NOV, cambioPlan(nit, "ACU-05-A", 16, 800_000));
        importar(NOV, cambioPlan(nit, "ACU-05-A", 16, 800_000));

        Invoice despues = vigente(nit, NOV);
        assertEquals(antes.getId(), despues.getId());
        assertEquals(2, despues.getItems().size());
        assertEquals(Money.of(773_500), despues.getTotalAmount());
        List<OrderUpdate> novedades = novedadesDe(nit, NOV);
        assertEquals(1, novedades.size(), "La novedad reenviada se actualiza, no se duplica");
        assertEquals(novedadId, novedades.get(0).getId(), "Conserva su identificador");
        assertEquals(1, facturasDelCliente(nit));
    }

    @Test
    @DisplayName("6. Dos clientes: la novedad de uno no toca la factura del otro ni le crea factura")
    void dosClientesNoSeMezclan() throws Exception {
        String nitA = "907700061";
        String nitB = "907700062";
        importar(NOV, Fila.sede(nitA, "ACU-6A-A", 500_000), Fila.sede(nitB, "ACU-6B-A", 700_000));
        generar(NOV, nitA);

        ImportResultDto imp = importar(NOV, cambioPlan(nitB, "ACU-6B-A", 16, 900_000));

        assertTrue(imp.messages().stream().anyMatch(m -> m.contains(nitB) && m.contains("aún no tiene factura")),
                "Mensajes: " + imp.messages());
        Invoice facturaA = vigente(nitA, NOV);
        assertEquals(1, facturaA.getItems().size());
        assertEquals(Money.of(595_000), facturaA.getTotalAmount());
        assertEquals(0, facturasDelCliente(nitB), "Procesar una novedad no crea facturas");

        // Al generarla después, la novedad pendiente entra y queda ligada a la factura. Aunque
        // no hay factura previa, la novedad guardó la tarifa anterior (700.000): el cargo fijo
        // sale con ella y el cambio como incremento, no el mes completo al plan nuevo.
        OrderUpdate novedadB = novedadesDe(nitB, NOV).get(0);
        assertEquals(Money.of(700_000), novedadB.getPreviousValue(), "La novedad guarda la tarifa que tenía la sede");
        Invoice facturaB = generar(NOV, nitB);
        List<InvoiceItem> itemsB = itemsOrdenados(facturaB);
        assertEquals(2, itemsB.size());
        assertEquals(Money.of(700_000), itemsB.get(0).getTaxableBase());
        assertEquals(Money.of(100_000), itemsB.get(1).getTaxableBase()); // (900.000 − 700.000) × 15/30
        assertEquals(Money.of(800_000), facturaB.getSubtotalAmount());
        assertEquals(facturaB.getId(), novedadesDe(nitB, NOV).get(0).getInvoiceId());
    }

    @Test
    @DisplayName("El orden no importa: novedad antes de generar da lo mismo que generar y luego la novedad")
    void novedadAntesDeGenerarDaElMismoResultado() throws Exception {
        String nit = "907700020";
        importar(NOV, Fila.sede(nit, "ACU-20-A", 500_000));
        importar(NOV, cambioPlan(nit, "ACU-20-A", 16, 800_000));  // antes de la factura
        importar(NOV, cambioPlan(nit, "ACU-20-A", 16, 800_000));  // reenvío: no pisa la tarifa anterior
        Invoice factura = generar(NOV, nit);

        assertEquals(Money.of(500_000), novedadesDe(nit, NOV).get(0).getPreviousValue());
        assertEquals(Money.of(650_000), factura.getSubtotalAmount()); // 500.000 + 150.000, igual que en la prueba 2
        assertEquals(Money.of(773_500), factura.getTotalAmount());
    }

    @Test
    @DisplayName("7. Mismo cliente, periodos distintos: solo cambia la factura del periodo de la novedad")
    void mismoClientePeriodosDistintos() throws Exception {
        String nit = "907700007";
        importar(NOV, Fila.sede(nit, "ACU-07-A", 500_000));
        generar(NOV, nit);
        importar(DIC, Fila.sede(nit, "ACU-07-A", 500_000));
        generar(DIC, nit);

        importar(DIC, Fila.novedad(nit, "ACU-07-A", "CAMBIO_PLAN", LocalDate.of(2026, 12, 16), 800_000));

        Invoice noviembre = vigente(nit, NOV);
        assertEquals(1, noviembre.getItems().size());
        assertEquals(Money.of(595_000), noviembre.getTotalAmount());

        Invoice diciembre = vigente(nit, DIC);
        assertEquals(2, diciembre.getItems().size());
        assertEquals(Money.of(650_000), diciembre.getSubtotalAmount()); // 31 días: 15 + 15 comerciales
        assertEquals(2, facturasDelCliente(nit));
    }

    @Test
    @DisplayName("8. Factura que ya no está en preparación (con CUFE): no se modifica y la novedad queda pendiente")
    void facturaNoEditable() throws Exception {
        String nit = "907700008";
        importar(NOV, Fila.sede(nit, "ACU-08-A", 500_000));
        Invoice emitida = generar(NOV, nit);
        emitida.setCufe("CUFE-PRUEBA-0001");
        repositorioFactura.saveAndFlush(emitida);

        ImportResultDto imp = importar(NOV, cambioPlan(nit, "ACU-08-A", 16, 800_000));

        assertTrue(imp.messages().stream().anyMatch(m -> m.contains("no se modificó") && m.contains("CUFE")),
                "Mensajes: " + imp.messages());
        Invoice actual = vigente(nit, NOV);
        assertEquals(1, actual.getItems().size());
        assertEquals(Money.of(595_000), actual.getTotalAmount());
        assertNull(novedadesDe(nit, NOV).get(0).getInvoiceId(), "La novedad no se marca como facturada");
        assertEquals(1, facturasDelCliente(nit));
    }

    @Test
    @DisplayName("Una novedad efectiva después del cierre del periodo se rechaza y no se registra")
    void novedadFueraDelPeriodoSeRechaza() throws Exception {
        String nit = "907700010";
        importar(NOV, Fila.sede(nit, "ACU-10-A", 500_000));
        generar(NOV, nit);

        ImportResultDto imp = importar(NOV,
                Fila.novedad(nit, "ACU-10-A", "CAMBIO_PLAN", LocalDate.of(2026, 12, 5), 800_000));

        assertEquals(1, imp.errorRecords());
        assertTrue(imp.messages().stream().anyMatch(m -> m.contains("Impórtela en el periodo 2026-12")),
                "Mensajes: " + imp.messages());
        assertTrue(novedadesDe(nit, NOV).isEmpty());
        assertEquals(Money.of(595_000), vigente(nit, NOV).getTotalAmount());
    }

    @Autowired private InvoiceChargeBreakdownService breakdownService;

    @Test
    @DisplayName("Detalle de cobros: cargos fijos = plan del mes; otros conceptos = cada novedad; los bloques suman el total")
    void detalleDeCobrosPorBloques() throws Exception {
        String nit = "907700012";
        importar(NOV, Fila.sede(nit, "ACU-12-A", 500_000));
        generar(NOV, nit);
        importar(NOV, cambioPlan(nit, "ACU-12-A", 16, 800_000));
        importar(NOV, cambioPlan(nit, "ACU-12-A", 21, 1_100_000));

        var detalle = breakdownService.breakdown(vigente(nit, NOV));

        assertEquals(1, detalle.cargosFijos().lineas().size());
        assertEquals(0, detalle.cargosFijos().lineas().get(0).valor().compareTo(new java.math.BigDecimal("500000")));
        assertEquals(LocalDate.of(2026, 11, 1), detalle.cargosFijos().lineas().get(0).fechaInicial());
        assertEquals(LocalDate.of(2026, 11, 30), detalle.cargosFijos().lineas().get(0).fechaFinal());
        assertEquals(0, detalle.cargosFijos().total().compareTo(new java.math.BigDecimal("595000")));

        var otros = detalle.otrosConceptos().lineas();
        assertEquals(2, otros.size());
        assertTrue(otros.get(0).descripcion().startsWith("Cambio de plan a $ 800.000"), otros.get(0).descripcion());
        assertTrue(otros.get(0).descripcion().contains("(orden ACU-12-A)") && otros.get(0).descripcion().contains("desde 16-nov-2026"),
                "Cada novedad dice de dónde viene: " + otros.get(0).descripcion());
        assertEquals("Novedades del servicio", otros.get(0).categoria());
        assertEquals(0, otros.get(0).valor().compareTo(new java.math.BigDecimal("150000")));
        assertEquals(0, otros.get(1).valor().compareTo(new java.math.BigDecimal("100000")));
        assertEquals(0, detalle.otrosConceptos().total().compareTo(new java.math.BigDecimal("297500")));

        assertEquals(0, detalle.cargosFijos().total().add(detalle.otrosConceptos().total()).compareTo(detalle.totalFactura()),
                "Los bloques suman exactamente el total de la factura");
        assertEquals(0, detalle.totalFactura().compareTo(new java.math.BigDecimal("892500")));

        // La representación gráfica (PDF) incluye los bloques.
        String html = billingService.renderInvoiceHtml(vigente(nit, NOV).getId());
        assertTrue(html.contains("CONOCE LOS COBROS A DETALLE"));
        assertTrue(html.contains("DEUDA ANTERIOR") && html.contains("CARGOS FIJOS") && html.contains("OTROS CONCEPTOS"));
        assertTrue(html.contains("Cambio de plan a $ 800.000"));
    }

    @Test
    @DisplayName("Detalle de cobros: la bajada de plan aparece como descuento en otros conceptos y los bloques cuadran")
    void detalleDeCobrosConBajadaDePlan() throws Exception {
        String nit = "907700013";
        importar(NOV, Fila.sede(nit, "ACU-13-A", 500_000));
        generar(NOV, nit);
        importar(NOV, cambioPlan(nit, "ACU-13-A", 16, 300_000));

        Invoice factura = vigente(nit, NOV);
        var detalle = breakdownService.breakdown(factura);

        assertEquals(0, detalle.cargosFijos().subtotal().compareTo(new java.math.BigDecimal("500000")));
        var descuento = detalle.otrosConceptos().lineas().get(0);
        assertTrue(descuento.descripcion().startsWith("Descuento por bajada de plan"));
        assertEquals("Ajustes y descuentos", descuento.categoria());
        assertEquals(0, descuento.valor().compareTo(new java.math.BigDecimal("-100000")));
        assertEquals(0, detalle.cargosFijos().total().add(detalle.otrosConceptos().total()).compareTo(detalle.totalFactura()));
        assertEquals(Money.of(476_000), factura.getTotalAmount());
    }

    @Test
    @DisplayName("Deuda anterior: saldo de los documentos de Siigo previos al periodo; el total a pagar lo suma")
    void deudaAnteriorDesdeDocumentosDeSiigo() throws Exception {
        String nit = "907700014";
        importar(NOV, Fila.sede(nit, "ACU-14-A", 500_000));
        Invoice actual = generar(NOV, nit);

        var cliente = repositorioCliente.findByNit(nit).orElseThrow();
        var octubre = repositorioPeriodo.findByCode("2026-10").orElseThrow();
        Invoice anterior = new Invoice(UUID.randomUUID(), cliente.getId(), octubre.getId(), BillingType.F1, cliente,
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 20));
        anterior.setInvoiceNumber("88001");
        anterior.markImportedFromSiigo("F1:88001");
        anterior.addItem(InvoiceItem.fromOfficialAmounts(anterior.getId(), null, "SERVICIO OCTUBRE", java.math.BigDecimal.ONE,
                Money.of(100_000), Money.of(19_000)));
        anterior.consolidate();
        repositorioFactura.saveAndFlush(anterior);

        var deuda = breakdownService.breakdown(vigente(nit, NOV)).deudaAnterior();

        assertEquals(1, deuda.facturas());
        assertEquals(0, deuda.cargosAnteriores().compareTo(new java.math.BigDecimal("119000")));
        assertEquals(0, deuda.pagosRecibidos().compareTo(java.math.BigDecimal.ZERO));
        assertEquals(0, deuda.saldo().compareTo(new java.math.BigDecimal("119000")));
        var detalle = breakdownService.breakdown(vigente(nit, NOV));
        assertEquals(0, detalle.totalAPagar().compareTo(actual.getTotalAmount().amount().add(new java.math.BigDecimal("119000"))));
    }

    @Test
    @DisplayName("La tarifa de apertura de un mes es la del cierre del anterior, no la de su día 1")
    void tarifaDeAperturaEsLaDelCierreDelMesAnterior() throws Exception {
        String nit = "907700015";
        importar(NOV, Fila.sede(nit, "ACU-15-A", 500_000));
        generar(NOV, nit);
        importar(NOV, cambioPlan(nit, "ACU-15-A", 16, 800_000)); // noviembre cierra en 800.000

        // El cambio de diciembre llega antes de emitir diciembre.
        importar(DIC, Fila.novedad(nit, "ACU-15-A", "CAMBIO_PLAN", LocalDate.of(2026, 12, 16), 1_100_000));
        Invoice diciembre = generar(DIC, nit);

        List<InvoiceItem> items = itemsOrdenados(diciembre);
        assertEquals(Money.of(800_000), items.get(0).getTaxableBase(), "Cargo fijo de diciembre al plan con que cerró noviembre");
        assertEquals(Money.of(150_000), items.get(1).getTaxableBase()); // (1.100.000 − 800.000) × 15/30
        assertEquals(Money.of(950_000), diciembre.getSubtotalAmount());
    }

    /** Documento oficial de Siigo con los conceptos dados (código, descripción, base sin IVA). */
    private Invoice documentoSiigo(String nit, String numero, LocalDate emision, String[]... conceptos) {
        var cliente = repositorioCliente.findByNit(nit).orElseThrow();
        var periodo = repositorioPeriodo.findByCode(java.time.YearMonth.from(emision).toString()).orElseThrow();
        Invoice doc = new Invoice(UUID.randomUUID(), cliente.getId(), periodo.getId(), BillingType.F1, cliente,
                emision, emision.plusDays(15));
        doc.setInvoiceNumber(numero);
        doc.markImportedFromSiigo("F1:" + numero);
        for (String[] c : conceptos) {
            Money base = Money.of(new java.math.BigDecimal(c[2]));
            doc.addItem(InvoiceItem.fromOfficialAmounts(doc.getId(), c[0], c[1], java.math.BigDecimal.ONE,
                    base, base.percentage(java.math.BigDecimal.valueOf(19))));
        }
        doc.consolidate();
        return repositorioFactura.saveAndFlush(doc);
    }

    @Test
    @DisplayName("Facturas de Siigo: el detalle por bloques clasifica sus conceptos por código y descripción")
    void detalleDeCobrosDeDocumentoSiigo() throws Exception {
        String nit = "907700016";
        importar(NOV, Fila.sede(nit, "ACU-16-A", 500_000)); // crea el cliente y el periodo

        Invoice doc = documentoSiigo(nit, "88101", LocalDate.of(2026, 11, 3),
                new String[] {"10-30-13", "INT.FIB.CORP.200MB GR 01-30 NOV 2026", "500000"},
                new String[] {"NOV-UPGRADE-15D", "Novedad incremento de plan (diferencial 15 días)", "150000"},
                new String[] {"10-40-01", "CUOTA INSTALACION ENLACE (1/3)", "60000"},
                new String[] {"10-30-13", "SEDE NORTE INT.FIB.CORP.50MB GR 16-30 NOV 2026", "100000"});

        var detalle = breakdownService.breakdown(doc);

        assertEquals(1, detalle.cargosFijos().lineas().size(), "Solo el servicio del mes completo es cargo fijo");
        var fijo = detalle.cargosFijos().lineas().get(0);
        assertEquals(LocalDate.of(2026, 11, 1), fijo.fechaInicial(), "Fechas tomadas de 'GR 01-30 NOV 2026'");
        assertEquals(LocalDate.of(2026, 11, 30), fijo.fechaFinal());
        var otros = detalle.otrosConceptos().lineas();
        assertEquals(3, otros.size(), "Novedad, cuota y alta a mitad de mes");
        // Agrupadas por subtítulo, en orden: primero novedades, luego cuotas.
        assertEquals("Novedades del servicio", otros.get(0).categoria());
        assertEquals("Novedades del servicio", otros.get(1).categoria());
        assertEquals("Cuotas de instalación", otros.get(2).categoria());
        assertEquals(0, detalle.cargosFijos().total().add(detalle.otrosConceptos().total()).compareTo(detalle.totalFactura()),
                "Los bloques suman el total del documento");

        // La representación gráfica generada también lleva los bloques.
        assertTrue(billingService.renderInvoiceHtml(doc.getId()).contains("CONOCE LOS COBROS A DETALLE"));
    }

    @Test
    @DisplayName("Facturas de Siigo cargadas desde cartera (concepto genérico): un único cargo fijo y la deuda anterior")
    void detalleDeCobrosDeDocumentoDeCartera() throws Exception {
        String nit = "907700017";
        importar(NOV, Fila.sede(nit, "ACU-17-A", 500_000));
        documentoSiigo(nit, "88201", LocalDate.of(2026, 10, 5),
                new String[] {"SERV-CORP", "Facturación de servicios corporativos", "100000"});
        Invoice actual = documentoSiigo(nit, "88202", LocalDate.of(2026, 11, 5),
                new String[] {"SERV-CORP", "Facturación de servicios corporativos", "200000"});

        var detalle = breakdownService.breakdown(actual);

        assertEquals(1, detalle.cargosFijos().lineas().size());
        assertTrue(detalle.otrosConceptos().lineas().isEmpty());
        assertEquals(1, detalle.deudaAnterior().facturas(), "La factura de octubre es deuda anterior de la de noviembre");
        assertEquals(0, detalle.deudaAnterior().saldo().compareTo(new java.math.BigDecimal("119000")));
    }

    @Autowired private IncomingInvoiceQueryService queryService;

    @Test
    @DisplayName("El listado muestra las facturas internas solo si se pide; el portal de pagos nunca las ofrece")
    void filtroDeOrigenDelListado() throws Exception {
        String nit = "907700011";
        importar(NOV, Fila.sede(nit, "ACU-11-A", 500_000));
        Invoice interna = generar(NOV, nit);
        entityManager.flush();

        var internas = queryService.search(nit, null, null, null, null, null, null, null, true, "INTERNO", 0, 25);
        assertEquals(1, internas.totalElements());
        assertEquals("INTERNO", internas.items().get(0).origen());
        assertEquals(interna.getId(), internas.items().get(0).id());

        assertEquals(0, queryService.search(nit, null, null, null, null, null, null, null, true, null, 0, 25).totalElements(),
                "Por omisión el listado sigue mostrando solo documentos de Siigo");
        assertEquals(1, queryService.search(nit, null, null, null, null, null, null, null, true, "TODAS", 0, 25).totalElements());

        var agrupadas = queryService.searchGroupedByNit(nit, null, null, null, null, null, null, null, true, "INTERNO", 0, 25);
        assertEquals("INTERNO", agrupadas.items().get(0).invoices().get(0).origen());

        // La pasarela cobra la factura del mes con sus novedades sin esperar a Siigo.
        var pasarela = queryService.findGroupByNit(nit).orElseThrow();
        assertEquals(1, pasarela.invoices().size());
        assertEquals(interna.getId(), pasarela.invoices().get(0).id());
        assertEquals(0, pasarela.totalPendingValue().compareTo(new java.math.BigDecimal("595000")));
    }

    @Test
    @DisplayName("Pasarela: la novedad se refleja de una vez en el valor a cobrar")
    void pasarelaCobraLaFacturaActualizada() throws Exception {
        String nit = "907700018";
        importar(NOV, Fila.sede(nit, "ACU-18-A", 500_000));
        generar(NOV, nit);
        importar(NOV, cambioPlan(nit, "ACU-18-A", 16, 800_000));

        var pasarela = queryService.findGroupByNit(nit).orElseThrow();
        assertEquals(0, pasarela.totalPendingValue().compareTo(new java.math.BigDecimal("773500")));
    }

    @Test
    @DisplayName("Pasarela: cuando Siigo devuelve el documento oficial de la interna, se cobra una sola vez")
    void pasarelaNoDuplicaInternaYSuDocumentoOficial() throws Exception {
        String nit = "907700019";
        importar(NOV, Fila.sede(nit, "ACU-19-A", 500_000));
        Invoice interna = generar(NOV, nit);
        String numero = interna.getInvoiceNumber().replaceAll("\\D", "");
        Invoice oficial = documentoSiigo(nit, numero, LocalDate.of(2026, 11, 3),
                new String[] {"SERV-CORP", "Facturación de servicios corporativos", "500000"});

        var pasarela = queryService.findGroupByNit(nit).orElseThrow();

        assertEquals(1, pasarela.invoices().size(), "La interna y su documento de Siigo son la misma factura");
        assertEquals(oficial.getId(), pasarela.invoices().get(0).id(), "Manda el documento oficial de Siigo");
        assertEquals(0, pasarela.totalPendingValue().compareTo(new java.math.BigDecimal("595000")));

        // Si la interna ya se pagó en la pasarela, el documento de Siigo no se ofrece otra vez.
        Invoice pagada = repositorioFactura.findById(interna.getId()).orElseThrow();
        pagada.markAsPaid(UUID.randomUUID(), "TX-PRUEBA");
        repositorioFactura.saveAndFlush(pagada);
        assertTrue(queryService.findGroupByNit(nit).isEmpty(), "No se cobra dos veces la misma factura");
    }
}
