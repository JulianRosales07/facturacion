package com.sistemaspalacios.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.dto.FacturaSpPagosDto;
import com.sistemaspalacios.billing.service.dto.OfficialInvoiceData;
import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor;
import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor.PdfExtractedMetadata;
import jakarta.persistence.EntityManager;
import org.springframework.test.context.transaction.AfterTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Documentos de Siigo: el corte de cartera crea el documento con un concepto genérico y el
 * PDF oficial (Docs/server/F001000000519930000.PDF, factura FESP 51993) aporta su detalle
 * real. Se verifica el JSON que recibe la pasarela. Los datos se crean con un consecutivo y
 * NIT de prueba y la transacción se revierte.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class SiigoPdfDetalleIntegrationTest {

    private static final File PDF_REAL = new File("Docs/server/F001000000519930000.PDF");
    private static final File PDF_F20_REAL = new File("Docs/archivosplanos/F020000000002870000.PDF");
    private static final String NIT = "907703001";

    @Autowired private OfficialInvoiceImportService officialImport;
    @Autowired private OfficialInvoiceDetailService detailService;
    @Autowired private PdfMetadataExtractor pdfExtractor;
    @Autowired private SpPagosQueryService spPagos;
    @Autowired private RepositorioFactura repositorioFactura;
    @Autowired private RepositorioPeriodo repositorioPeriodo;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService paymentService;
    @Autowired private com.sistemaspalacios.billing.repository.RepositorioVigenciaConcepto repositorioVigenciaConcepto;

    private final List<UUID> creadas = new ArrayList<>();

    /** El historial se escribe en su propia transacción (REQUIRES_NEW): se limpia después del rollback de la prueba. */
    @AfterTransaction
    void limpiarHistorial() {
        creadas.forEach(id -> jdbc.update("DELETE FROM historial_recepcion_archivo WHERE sha256 = ?", "pdf-detalle:" + id));
    }

    /** Fila del corte de cartera: un concepto genérico por el valor de la fila. */
    private Invoice desdeCartera(String numero, long valor) {
        Invoice invoice = officialImport.importDocument(new OfficialInvoiceData(NIT, null, BillingType.F1, numero,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 25), null, Money.of(valor),
                List.of(new OfficialInvoiceData.Item(Invoice.CARTERA_PLACEHOLDER_CODE, Invoice.CARTERA_PLACEHOLDER_DESCRIPTION,
                        BigDecimal.ONE, Money.of(valor), Money.ZERO))));
        creadas.add(invoice.getId());
        return invoice;
    }

    private Invoice releer(UUID id) {
        entityManager.flush();
        entityManager.clear();
        return repositorioFactura.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("Cartera + PDF real: el JSON de la pasarela trae los 5 conceptos, base 4.785.000, IVA 909.150 y periodo del servicio")
    void pdfRealCompletaElDocumentoYElJson() throws Exception {
        Invoice doc = desdeCartera("88993", 5_694_150);
        assertTrue(doc.hasCarteraPlaceholderDetail());
        PdfExtractedMetadata meta = pdfExtractor.extractMetadata(PDF_REAL);

        var result = detailService.applyPdfDetail(doc, meta, PDF_REAL.getName());

        assertEquals(OfficialInvoiceDetailService.Outcome.APLICADO, result.outcome(), result.detail());
        Invoice actual = releer(doc.getId());
        assertEquals(5, actual.getItems().size());
        assertEquals(Money.of(new BigDecimal("4785000.00")), actual.getSubtotalAmount());
        assertEquals(Money.of(new BigDecimal("909150.00")), actual.getIvaAmount());
        assertEquals(Money.of(new BigDecimal("5694150.00")), actual.getTotalAmount());
        assertEquals("2026-08", repositorioPeriodo.findById(actual.getPeriodId()).orElseThrow().getCode(),
                "Periodo del servicio (GR 01-31 AGT 2026), no el mes de emisión");

        FacturaSpPagosDto json = spPagos.getInvoiceForPayment(doc.getId());
        assertEquals("2026-08", json.periodo());
        assertEquals(new BigDecimal("4785000.00"), json.importes().subtotal());
        assertEquals(new BigDecimal("909150.00"), json.importes().iva());
        assertEquals(new BigDecimal("5694150.00"), json.importes().totalOficial());
        assertEquals(new BigDecimal("5694150.00"), json.importes().saldoPendiente());
        assertTrue(json.pagable());
        assertEquals(5, json.items().size());
        assertEquals("0100040000021", json.items().get(0).codigo());
        assertTrue(json.items().stream().anyMatch(i -> i.descripcion().equals("LA CRUZ.INT.FIB.DEDI.1.65 GB GR 01-31 AGT 2026")));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        System.out.println("JSON_PASARELA_EJEMPLO\n" + mapper.writeValueAsString(json));

        // Idempotencia: el mismo PDF otra vez no cambia nada.
        var repetido = detailService.applyPdfDetail(actual, meta, PDF_REAL.getName());
        assertEquals(OfficialInvoiceDetailService.Outcome.YA_CONSISTENTE, repetido.outcome());
        assertEquals(5, releer(doc.getId()).getItems().size());
    }

    @Test
    @DisplayName("Si la fila de cartera traía un saldo (abono previo), el total pasa a ser el del PDF y el saldo se conserva")
    void saldoDeCarteraSeConservaComoSaldoPendiente() {
        Invoice doc = desdeCartera("88994", 2_000_000); // el cliente ya abonó: la cartera dice que debe 2.000.000

        detailService.applyPdfDetail(doc, pdfExtractor.extractMetadata(PDF_REAL), PDF_REAL.getName());

        Invoice actual = releer(doc.getId());
        assertEquals(Money.of(new BigDecimal("5694150.00")), actual.getTotalAmount(), "Total oficial del documento");
        FacturaSpPagosDto json = spPagos.getInvoiceForPayment(doc.getId());
        assertEquals(new BigDecimal("2000000.00"), json.importes().saldoPendiente(), "Lo que se cobra es el saldo de la cartera");
        assertEquals("CORTE_CARTERA_SIIGO", json.importes().fuenteSaldo());
    }

    @Test
    @DisplayName("Reimportar el corte de cartera después de aplicar el detalle no lo rechaza ni lo pisa")
    void reimportarCarteraNoPisaElDetalle() {
        Invoice doc = desdeCartera("88995", 5_694_150);
        detailService.applyPdfDetail(doc, pdfExtractor.extractMetadata(PDF_REAL), PDF_REAL.getName());
        entityManager.flush();

        Invoice otraVez = officialImport.importDocument(new OfficialInvoiceData(NIT, null, BillingType.F1, "88995",
                doc.getIssueDate(), doc.getDueDate(), null, Money.ZERO,
                List.of(new OfficialInvoiceData.Item(Invoice.CARTERA_PLACEHOLDER_CODE, Invoice.CARTERA_PLACEHOLDER_DESCRIPTION,
                        BigDecimal.ONE, Money.ZERO, Money.ZERO)), Money.of(3_000_000), java.time.Instant.now()));

        assertEquals(doc.getId(), otraVez.getId(), "Es el mismo documento");
        assertEquals(5, releer(doc.getId()).getItems().size(), "El detalle oficial se conserva");
        assertEquals(Money.of(3_000_000), otraVez.getPendingBalance(), "La reimportación sí actualiza el saldo");
    }

    @Test
    @DisplayName("Detalle ya cargado distinto al del PDF: conflicto registrado, sin sobrescribir")
    void conflictoNoSobrescribe() {
        Invoice doc = officialImport.importDocument(new OfficialInvoiceData(NIT, null, BillingType.F1, "88996",
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 25), null, Money.of(1_190_000),
                List.of(new OfficialInvoiceData.Item("SERV-1", "SEDE X GR 01-31 AGT 2026", BigDecimal.ONE,
                        Money.of(1_000_000), Money.of(190_000)))));
        creadas.add(doc.getId());

        var result = detailService.applyPdfDetail(doc, pdfExtractor.extractMetadata(PDF_REAL), PDF_REAL.getName());

        assertEquals(OfficialInvoiceDetailService.Outcome.CONFLICTO, result.outcome());
        assertTrue(result.detail().contains("requiere conciliación"));
        Invoice actual = releer(doc.getId());
        assertEquals(1, actual.getItems().size());
        assertEquals(Money.of(1_190_000), actual.getTotalAmount());
        Integer registrado = jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_recepcion_archivo WHERE sha256 = ? AND estado = 'RECHAZADO'", Integer.class,
                "pdf-detalle:" + doc.getId());
        assertEquals(1, registrado, "El conflicto queda en el historial");
    }

    @Test
    @DisplayName("Un PDF cuyo detalle no cuadra no se usa: el documento queda como estaba")
    void pdfInconsistenteNoSeUsa() {
        Invoice doc = desdeCartera("88997", 5_694_150);
        PdfExtractedMetadata real = pdfExtractor.extractMetadata(PDF_REAL);
        PdfExtractedMetadata alterado = new PdfExtractedMetadata(real.invoiceNumber(), real.cufe(), real.clientNit(), true,
                null, null, null, null, real.issueDate(), real.dueDate(), new BigDecimal("6000000.00"),
                real.items(), real.grossAmount(), real.ivaAmount(), "La suma de las líneas no coincide con el Total a Pagar");

        var result = detailService.applyPdfDetail(doc, alterado, PDF_REAL.getName());

        assertEquals(OfficialInvoiceDetailService.Outcome.SIN_DETALLE, result.outcome());
        assertTrue(releer(doc.getId()).hasCarteraPlaceholderDetail());
    }

    @Test
    @DisplayName("Documento pagado: el PDF no cambia sus conceptos, periodo ni estado; queda para conciliación")
    void documentoPagadoNoSeModifica() {
        Invoice doc = desdeCartera("88998", 5_694_150);
        doc.markAsPaid();
        repositorioFactura.saveAndFlush(doc);
        UUID periodoAntes = doc.getPeriodId();

        var result = detailService.applyPdfDetail(doc, pdfExtractor.extractMetadata(PDF_REAL), PDF_REAL.getName());

        assertEquals(OfficialInvoiceDetailService.Outcome.CON_PAGOS, result.outcome(), result.detail());
        assertTrue(result.detail().contains("Requiere conciliación"));
        Invoice actual = releer(doc.getId());
        assertTrue(actual.hasCarteraPlaceholderDetail(), "Conserva el concepto con el que se pagó");
        assertEquals(com.sistemaspalacios.billing.domain.model.InvoiceStatus.PAID, actual.getStatus());
        assertEquals(periodoAntes, actual.getPeriodId());
        assertEquals(Money.of(5_694_150), actual.getTotalAmount());
        Integer registrado = jdbc.queryForObject(
                "SELECT COUNT(*) FROM historial_recepcion_archivo WHERE sha256 = ? AND estado = 'RECHAZADO'", Integer.class,
                "pdf-detalle:" + doc.getId());
        assertEquals(1, registrado, "Queda constancia para conciliación");
    }

    private PaymentService.ConceptSelection concepto(com.sistemaspalacios.billing.domain.model.TaxConceptCode code, String name,
                                                     String percentage, long minimumBase) {
        var concept = repositorioVigenciaConcepto.saveAndFlush(new com.sistemaspalacios.billing.domain.model.TaxConceptValidity(
                null, name, 91, code, null, null, Money.of(minimumBase), new BigDecimal(percentage), LocalDate.of(2026, 1, 1), null, true));
        return new PaymentService.ConceptSelection(concept.getId(), concept.getMaxPercentage());
    }

    private List<PaymentService.ConceptSelection> retencionesDePrueba() {
        var codes = com.sistemaspalacios.billing.domain.model.TaxConceptCode.class;
        return List.of(
                concepto(Enum.valueOf(codes, "RETEFUENTE"), "ReteFuente 4% (prueba PDF)", "4.00", 150_000),
                concepto(Enum.valueOf(codes, "RETEICA"), "ReteICA 0,6% (prueba PDF)", "0.60", 0),
                concepto(Enum.valueOf(codes, "RETEIVA"), "ReteIVA 15% (prueba PDF)", "15.00", 0));
    }

    @Test
    @DisplayName("Con el detalle del PDF, las retenciones se liquidan sobre la base 4.785.000 y el IVA 909.150, no sobre el total")
    void retencionesSobreLasBasesDelDetalle() {
        Invoice doc = desdeCartera("88999", 5_694_150);
        detailService.applyPdfDetail(doc, pdfExtractor.extractMetadata(PDF_REAL), PDF_REAL.getName());
        entityManager.flush();

        var sim = paymentService.simulate(new PaymentService.PaymentSimulationCommand(NIT, List.of(doc.getId()), retencionesDePrueba()));

        assertEquals(Money.of(5_694_150), sim.totalInvoices());
        assertEquals(Money.of(4_785_000), sim.taxableBase());
        assertEquals(Money.of(909_150), sim.totalIva());
        var rf = sim.withholdings().get(0);
        assertEquals(Money.of(4_785_000), rf.evaluatedBase());
        assertEquals(Money.of(191_400), rf.withheldAmount());
        var ica = sim.withholdings().get(1);
        assertEquals(Money.of(4_785_000), ica.evaluatedBase());
        assertEquals(Money.of(28_710), ica.withheldAmount());
        var reteIva = sim.withholdings().get(2);
        assertEquals(Money.of(909_150), reteIva.evaluatedBase());
        assertEquals(Money.of(new BigDecimal("136372.50")), reteIva.withheldAmount());
    }

    @Test
    @DisplayName("Con saldo parcial no se calculan retenciones reales sobre bases proporcionales sin conciliar")
    void retencionesSobreSaldoParcial() {
        Invoice doc = desdeCartera("89000", 2_000_000);
        detailService.applyPdfDetail(doc, pdfExtractor.extractMetadata(PDF_REAL), PDF_REAL.getName());
        entityManager.flush();

        assertThrows(PaymentConflictException.class, () -> paymentService.simulate(
                new PaymentService.PaymentSimulationCommand(NIT, List.of(doc.getId()), retencionesDePrueba())));
        var withoutWithholdings = paymentService.simulate(new PaymentService.PaymentSimulationCommand(NIT, List.of(doc.getId()), List.of()));
        assertEquals(Money.of(2_000_000), withoutWithholdings.totalInvoices());
        assertEquals(Money.of(5_694_150), doc.getTotalAmount(), "El documento oficial se conserva");
    }

    @Test
    @DisplayName("PDF real F20 (FEMG 287): cartera + PDF → JSON con tipo F20, base 160.000, IVA 30.400 y periodo 2026-08")
    void pdfF20RealCompletaElDocumento() {
        Invoice doc = officialImport.importDocument(new OfficialInvoiceData(NIT, null, BillingType.F20, "88287",
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 25), null, Money.of(190_400),
                List.of(new OfficialInvoiceData.Item(Invoice.CARTERA_PLACEHOLDER_CODE, Invoice.CARTERA_PLACEHOLDER_DESCRIPTION,
                        BigDecimal.ONE, Money.of(190_400), Money.ZERO))));
        creadas.add(doc.getId());

        var result = detailService.applyPdfDetail(doc, pdfExtractor.extractMetadata(PDF_F20_REAL), PDF_F20_REAL.getName());

        assertEquals(OfficialInvoiceDetailService.Outcome.APLICADO, result.outcome(), result.detail());
        FacturaSpPagosDto json = spPagos.getInvoiceForPayment(doc.getId());
        assertEquals("F20", json.tipoFacturacion());
        assertEquals("2026-08", json.periodo());
        assertEquals(new BigDecimal("160000.00"), json.importes().subtotal());
        assertEquals(new BigDecimal("30400.00"), json.importes().iva());
        assertEquals(new BigDecimal("190400.00"), json.importes().totalOficial());
        assertEquals(1, json.items().size());
        assertEquals("NT.FIB.CORP.200MB GR 01-31 AGT 2026", json.items().get(0).descripcion());
    }

    @Test
    @DisplayName("Las facturas internas no se tocan con el detalle de un PDF de Siigo")
    void facturaInternaNoAplica() {
        Invoice interna = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BillingType.F1, null,
                LocalDate.of(2026, 9, 10), null);
        interna.addItem(new InvoiceItem(null, null, com.sistemaspalacios.billing.domain.model.BillableItemType.CORPORATE_SERVICE,
                null, "X", BigDecimal.ONE, Money.of(100), BigDecimal.ZERO, true, BigDecimal.valueOf(19)));
        var result = detailService.applyPdfDetail(interna, pdfExtractor.extractMetadata(PDF_REAL), PDF_REAL.getName());
        assertEquals(OfficialInvoiceDetailService.Outcome.NO_APLICA, result.outcome());
    }
}
