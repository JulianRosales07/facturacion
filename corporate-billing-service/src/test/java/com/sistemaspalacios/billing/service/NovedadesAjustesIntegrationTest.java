package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.NoveltyProcessingStatus;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioNovedad;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import com.sistemaspalacios.billing.service.NovedadesFacturaEnPreparacionTestSupport.Fila;
import com.sistemaspalacios.billing.service.dto.ImportResultDto;
import jakarta.persistence.EntityManager;
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
 * Novedades que llegan cuando la factura de su periodo ya fue emitida o pagada (escenarios
 * C y D): el documento no se toca y el impacto se cobra o compensa en la siguiente factura
 * en preparación del cliente. Sede de referencia: 500.000/mes; noviembre y diciembre de 2026.
 * Cada prueba revierte su transacción.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class NovedadesAjustesIntegrationTest {

    private static final String NOV = "2026-11";
    private static final String DIC = "2026-12";

    @Autowired private OrderImportService importService;
    @Autowired private OrderServiceImpl orderService;
    @Autowired private BillingService billingService;
    @Autowired private ClientNoveltyService noveltyService;
    @Autowired private InvoiceChargeBreakdownService breakdownService;
    @Autowired private RepositorioFactura repositorioFactura;
    @Autowired private RepositorioCliente repositorioCliente;
    @Autowired private RepositorioPeriodo repositorioPeriodo;
    @Autowired private RepositorioNovedad repositorioNovedad;
    @Autowired private RepositorioSede repositorioSede;
    @Autowired private EntityManager entityManager;

    private ImportResultDto importar(String periodo, Fila... filas) throws Exception {
        return importService.importFile("ajustes.xlsx", NovedadesFacturaEnPreparacionTestSupport.excel(List.of(filas)),
                periodo, OrderOrigin.SPE, "EXCEL");
    }

    private Invoice generar(String periodo, String nit) {
        return billingService.generateConsolidatedInvoice(periodo, BillingType.F1, BillingCutType.ADVANCE, nit);
    }

    private Invoice releer(UUID id) {
        entityManager.flush();
        entityManager.clear();
        return repositorioFactura.findById(id).orElseThrow();
    }

    /** La factura queda emitida: con CUFE ya no está en preparación. */
    private Invoice emitir(Invoice invoice) {
        Invoice f = repositorioFactura.findById(invoice.getId()).orElseThrow();
        f.setCufe("CUFE-" + f.getInvoiceNumber());
        return repositorioFactura.saveAndFlush(f);
    }

    private OrderUpdate unicaNovedad(String nit) {
        entityManager.flush();
        entityManager.clear();
        UUID clientId = repositorioCliente.findByNit(nit).orElseThrow().getId();
        List<OrderUpdate> all = repositorioSede.findByClientId(clientId).stream()
                .flatMap(s -> repositorioNovedad.findBySiteId(s.getId()).stream()).toList();
        assertEquals(1, all.size(), "Se esperaba una sola novedad");
        return all.get(0);
    }

    private static Fila cambio(String nit, String orden, LocalDate fecha, long valor) {
        return Fila.novedad(nit, orden, "CAMBIO_PLAN", fecha, valor);
    }

    private Invoice documentoSiigo(String nit, String numero, LocalDate emision, long base) {
        var cliente = repositorioCliente.findByNit(nit).orElseThrow();
        var periodo = repositorioPeriodo.findByCode(java.time.YearMonth.from(emision).toString()).orElseThrow();
        Invoice doc = new Invoice(UUID.randomUUID(), cliente.getId(), periodo.getId(), BillingType.F1, cliente,
                emision, emision.plusDays(15));
        doc.setInvoiceNumber(numero);
        doc.markImportedFromSiigo("F1:" + numero);
        doc.addItem(InvoiceItem.fromOfficialAmounts(doc.getId(), "SERV-CORP", "Facturación de servicios corporativos",
                BigDecimal.ONE, Money.of(base), Money.of(base).percentage(BigDecimal.valueOf(19))));
        doc.consolidate();
        return repositorioFactura.saveAndFlush(doc);
    }

    @Test
    @DisplayName("C. Factura emitida: no se modifica; el incremento pasa como ajuste a la factura siguiente")
    void facturaEmitidaPasaElAjusteALaSiguiente() throws Exception {
        String nit = "907701001";
        importar(NOV, Fila.sede(nit, "AJU-01-A", 500_000));
        Invoice noviembre = emitir(generar(NOV, nit));

        ImportResultDto imp = importar(NOV, cambio(nit, "AJU-01-A", LocalDate.of(2026, 11, 16), 800_000));

        assertTrue(imp.messages().stream().anyMatch(m -> m.contains("no se modificó") && m.contains("ajuste")),
                "Mensajes: " + imp.messages());
        assertEquals(Money.of(595_000), releer(noviembre.getId()).getTotalAmount(), "El documento emitido conserva su valor");
        OrderUpdate novedad = unicaNovedad(nit);
        assertEquals(NoveltyProcessingStatus.PENDIENTE_AJUSTE, novedad.getProcessingStatus());
        assertEquals(Money.of(150_000), novedad.getImpactAmount()); // (800.000 − 500.000) × 15/30
        assertNull(novedad.getInvoiceId());

        Invoice diciembre = releer(generar(DIC, nit).getId());

        // Cargo fijo al plan con que cerró noviembre + el ajuste de noviembre.
        assertEquals(Money.of(950_000), diciembre.getSubtotalAmount());
        assertTrue(diciembre.getItems().stream().anyMatch(i -> i.getDescription().contains("AJUSTE CAMBIO PLAN PERIODO 2026-11")
                && i.getTaxableBase().equals(Money.of(150_000))));
        OrderUpdate aplicada = unicaNovedad(nit);
        assertEquals(NoveltyProcessingStatus.AJUSTE_APLICADO, aplicada.getProcessingStatus());
        assertEquals(diciembre.getId(), aplicada.getInvoiceId());

        var otros = breakdownService.breakdown(diciembre).otrosConceptos().lineas();
        assertTrue(otros.stream().anyMatch(l -> l.descripcion().startsWith("Ajuste de periodo anterior · Cambio de plan")
                && "Ajustes y descuentos".equals(l.categoria())), "Detalle: " + otros);
    }

    @Test
    @DisplayName("C. Si la factura siguiente ya existe, el ajuste entra de una vez (la pasarela ve el valor nuevo)")
    void ajusteActualizaDeUnaVezLaFacturaSiguiente() throws Exception {
        String nit = "907701002";
        importar(NOV, Fila.sede(nit, "AJU-02-A", 500_000));
        emitir(generar(NOV, nit));
        Invoice diciembre = generar(DIC, nit); // 500.000 + IVA
        assertEquals(Money.of(595_000), diciembre.getTotalAmount());

        ImportResultDto imp = importar(NOV, cambio(nit, "AJU-02-A", LocalDate.of(2026, 11, 16), 800_000));

        assertTrue(imp.messages().stream().anyMatch(m -> m.contains("pasan como ajuste a la factura " + diciembre.getInvoiceNumber())),
                "Mensajes: " + imp.messages());
        Invoice actualizada = releer(diciembre.getId());
        assertEquals(diciembre.getInvoiceNumber(), actualizada.getInvoiceNumber(), "Misma factura, sin documento nuevo");
        assertEquals(Money.of(950_000), actualizada.getSubtotalAmount(), "800.000 (plan desde el día 1) + 150.000 de ajuste");
    }

    @Test
    @DisplayName("D. Factura pagada con bajada de plan: el saldo a favor se compensa como descuento en la siguiente")
    void facturaPagadaCompensaElSaldoAFavor() throws Exception {
        String nit = "907701003";
        importar(NOV, Fila.sede(nit, "AJU-03-A", 500_000));
        Invoice noviembre = generar(NOV, nit);
        Invoice pagada = repositorioFactura.findById(noviembre.getId()).orElseThrow();
        pagada.markAsPaid(UUID.randomUUID(), "TX-AJU-03");
        repositorioFactura.saveAndFlush(pagada);

        importar(NOV, cambio(nit, "AJU-03-A", LocalDate.of(2026, 11, 16), 300_000));

        assertEquals(Money.of(-100_000), unicaNovedad(nit).getImpactAmount()); // (300.000 − 500.000) × 15/30
        assertEquals(Money.of(595_000), releer(noviembre.getId()).getTotalAmount(), "La factura pagada no cambia");

        Invoice diciembre = releer(generar(DIC, nit).getId());
        assertEquals(Money.of(200_000), diciembre.getSubtotalAmount(), "300.000 del plan nuevo − 100.000 a favor");
        assertTrue(diciembre.getItems().stream().allMatch(i -> !i.getTaxableBase().isNegative()), "Sin líneas negativas");
    }

    @Test
    @DisplayName("C'. Periodo ya facturado en Siigo: la novedad no genera otra factura del mes; va como ajuste")
    void periodoFacturadoEnSiigoVaComoAjuste() throws Exception {
        String nit = "907701004";
        importar(NOV, Fila.sede(nit, "AJU-04-A", 500_000));
        documentoSiigo(nit, "77104", LocalDate.of(2026, 11, 5), 500_000);

        ImportResultDto imp = importar(NOV, cambio(nit, "AJU-04-A", LocalDate.of(2026, 11, 16), 800_000));

        assertTrue(imp.messages().stream().anyMatch(m -> m.contains("ya fue facturado en Siigo") && m.contains("77104")),
                "Mensajes: " + imp.messages());
        assertEquals(NoveltyProcessingStatus.PENDIENTE_AJUSTE, unicaNovedad(nit).getProcessingStatus());

        Invoice diciembre = releer(generar(DIC, nit).getId());
        assertEquals(Money.of(950_000), diciembre.getSubtotalAmount());
    }

    @Test
    @DisplayName("Novedad retroactiva: importada en diciembre con fecha de noviembre, se registra en noviembre")
    void novedadRetroactivaVaASuPeriodo() throws Exception {
        String nit = "907701005";
        importar(NOV, Fila.sede(nit, "AJU-05-A", 500_000));
        emitir(generar(NOV, nit));
        Invoice diciembre = generar(DIC, nit);

        importar(DIC, cambio(nit, "AJU-05-A", LocalDate.of(2026, 11, 16), 800_000));

        OrderUpdate novedad = unicaNovedad(nit);
        assertEquals(repositorioPeriodo.findByCode(NOV).orElseThrow().getId(), novedad.getPeriodId(),
                "La novedad pertenece al periodo de su fecha efectiva");
        assertEquals(Money.of(950_000), releer(diciembre.getId()).getSubtotalAmount(),
                "Diciembre completo al plan nuevo + los días de noviembre como ajuste");
    }

    @Test
    @DisplayName("Idempotencia: reenviar la novedad y recalcular varias veces no duplica el ajuste")
    void ajusteNoSeDuplica() throws Exception {
        String nit = "907701006";
        importar(NOV, Fila.sede(nit, "AJU-06-A", 500_000));
        emitir(generar(NOV, nit));
        Invoice diciembre = generar(DIC, nit);

        importar(NOV, cambio(nit, "AJU-06-A", LocalDate.of(2026, 11, 16), 800_000));
        importar(NOV, cambio(nit, "AJU-06-A", LocalDate.of(2026, 11, 16), 800_000));
        var cliente = repositorioCliente.findByNit(nit).orElseThrow();
        billingService.refreshEditableInvoice(cliente.getId(), repositorioPeriodo.findByCode(DIC).orElseThrow().getId());
        billingService.refreshEditableInvoice(cliente.getId(), repositorioPeriodo.findByCode(DIC).orElseThrow().getId());

        Invoice actual = releer(diciembre.getId());
        assertEquals(Money.of(950_000), actual.getSubtotalAmount());
        assertEquals(1, actual.getItems().stream().filter(i -> i.getDescription().contains("AJUSTE")).count());
        assertEquals(NoveltyProcessingStatus.AJUSTE_APLICADO, unicaNovedad(nit).getProcessingStatus());
    }

    @Test
    @DisplayName("Impacto no calculable (renuncia al prorrateo sobre un periodo emitido): queda en revisión, no se cobra solo")
    void impactoNoCalculableQuedaEnRevision() throws Exception {
        String nit = "907701007";
        importar(NOV, Fila.sede(nit, "AJU-07-A", 500_000));
        emitir(generar(NOV, nit));
        Invoice diciembre = generar(DIC, nit);

        importar(NOV, new Fila(nit, "AJU-07-A", 800_000, "CAMBIO_PLAN", LocalDate.of(2026, 11, 16),
                "Acuerdo comercial: no cobrar prorrateo"));

        OrderUpdate novedad = unicaNovedad(nit);
        assertEquals(NoveltyProcessingStatus.PENDIENTE_AJUSTE, novedad.getProcessingStatus());
        assertNull(novedad.getImpactAmount());
        assertTrue(novedad.getProcessingDetail().contains("requiere revisión manual"));
        assertEquals(Money.of(800_000), releer(diciembre.getId()).getSubtotalAmount(), "Solo el plan nuevo, sin ajuste");

        var resumen = noveltyService.getClientNoveltiesSummary(nit, NOV);
        assertEquals("EN_REVISION", resumen.novedades().get(0).estado());
    }

    @Test
    @DisplayName("Reproceso seguro: una novedad registrada sin procesar se aplica al reprocesar, y repetirlo no cambia nada")
    void reprocesoEsIdempotente() throws Exception {
        String nit = "907701008";
        importar(NOV, Fila.sede(nit, "AJU-08-A", 500_000));
        Invoice noviembre = generar(NOV, nit);
        // Registrada directamente (como si el proceso se hubiera interrumpido antes de recalcular).
        orderService.recordUpdate(new com.sistemaspalacios.billing.service.ordenes.dto.OrderUpdateDto(
                "AJU-08-A", OrderOrigin.SPE, com.sistemaspalacios.billing.domain.model.OrderUpdateType.CAMBIO_PLAN,
                NOV, new BigDecimal("800000"), LocalDate.of(2026, 11, 16), true, "reintento"),
                repositorioPeriodo.findByCode(NOV).orElseThrow().getId(), Money.of(500_000));
        assertEquals(Money.of(595_000), releer(noviembre.getId()).getTotalAmount());

        var resumen = noveltyService.consumeAndSyncClientNovelties(nit, NOV);
        noveltyService.consumeAndSyncClientNovelties(nit, NOV);

        assertEquals(Money.of(773_500), releer(noviembre.getId()).getTotalAmount());
        assertEquals("CONSUMIDA", resumen.novedades().get(0).estado());
        assertEquals(0, resumen.novedades().get(0).valorAjuste().compareTo(new BigDecimal("150000")));
        // Trazabilidad para la pantalla: estado detallado y factura donde se cobró.
        assertEquals("APLICADA", resumen.novedades().get(0).estadoProcesamiento());
        assertEquals(noviembre.getInvoiceNumber(), resumen.novedades().get(0).numeroFactura());
        assertEquals(0, resumen.novedades().get(0).valorNuevo().compareTo(new BigDecimal("800000")));
    }

    @Test
    @DisplayName("Un NIT con varias sedes: cada novedad afecta solo su sede y todo va en la factura del cliente")
    void nitConVariasSedes() throws Exception {
        String nit = "907701009";
        importar(NOV, Fila.sede(nit, "AJU-09-A", 500_000), Fila.sede(nit, "AJU-09-B", 300_000));
        Invoice noviembre = generar(NOV, nit);

        importar(NOV, cambio(nit, "AJU-09-B", LocalDate.of(2026, 11, 16), 600_000));

        Invoice actual = releer(noviembre.getId());
        assertEquals(Money.of(950_000), actual.getSubtotalAmount()); // 500.000 + 300.000 + (300.000 × 15/30)
        assertEquals(1, repositorioFactura.findByClientId(actual.getClientId()).size());
    }
}
