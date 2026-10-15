package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.domain.model.ExportBatchStatus;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.ReceivedPdfRepository;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioLote;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración para Sprint 6 (Exportación a Siigo, Confirmación y Clasificación de PDFs).
 */
@SpringBootTest
@ActiveProfiles("postgres")
@TestPropertySource(properties = {
        "siigo.carpeta-compartida.path=build/test-siigo-shared",
        "siigo.pdf.local-path=build/test-siigo-pdfs"
})
@Transactional
class SiigoBatchIntegrationTest {

    @Autowired
    private BatchService batchService;

    @Autowired
    private SiigoPdfImporterService pdfImporterService;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioLote repositorioLote;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioSede repositorioSede;

    @Autowired
    private ReceivedPdfRepository receivedPdfRepository;

    private BillingPeriod testPeriod;
    private CorporateClient testClient;
    private Invoice testInvoice;
    private Path sharedFolderPath;

    @BeforeEach
    void setUp() throws Exception {
        receivedPdfRepository.deleteAll();

        sharedFolderPath = Paths.get("build/test-siigo-shared");
        Files.createDirectories(sharedFolderPath);

        // Limpiar archivos previos en carpeta de prueba
        File[] files = sharedFolderPath.toFile().listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }

        // 1. Periodo 2026-08
        testPeriod = repositorioPeriodo.findByCode("2026-08").orElseGet(() -> {
            BillingPeriod p = new BillingPeriod(
                    UUID.randomUUID(),
                    "2026-08",
                    BillingCutType.ADVANCE,
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 31),
                    true
            );
            return repositorioPeriodo.saveAndFlush(p);
        });

        // 2. Cliente Corporativo
        testClient = repositorioCliente.findByNit("900245045-1").orElseGet(() -> {
            CorporateClient c = new CorporateClient(
                    UUID.randomUUID(),
                    "900245045-1",
                    "ANDINO TELECOMUNICACIONES S.A.S",
                    "CALLE 19 # 24-02",
                    "facturacion@andino.com",
                    "3150000000",
                    (short) 1,
                    (short) 1,
                    BillingType.F1,
                    false,
                    "PASTO",
                    null
            );
            return repositorioCliente.saveAndFlush(c);
        });

        // 3. Sede de suscripción
        SubscriptionSite site = repositorioSede.findByExternalOrderCodeAndOrigin("ORD-SIIGO-BATCH-01", OrderOrigin.SPE).orElseGet(() -> {
            SubscriptionSite s = new SubscriptionSite(
                    UUID.randomUUID(),
                    testClient.getId(),
                    "ORD-SIIGO-BATCH-01",
                    OrderOrigin.SPE,
                    "Sede Central Pasto",
                    "Calle 19 # 24-02",
                    "Pasto",
                    Money.of(new BigDecimal("10000000.00")),
                    LocalDate.of(2026, 1, 1),
                    true
            );
            return repositorioSede.saveAndFlush(s);
        });

        // 4. Factura consolidada
        testInvoice = repositorioFactura.findCurrentByClientAndPeriod(testClient.getId(), testPeriod.getId())
                .orElseGet(() -> {
                    Invoice inv = new Invoice(
                            UUID.randomUUID(),
                            testClient.getId(),
                            testPeriod.getId(),
                            BillingType.F1,
                            testClient,
                            LocalDate.of(2026, 8, 31),
                            LocalDate.of(2026, 9, 15)
                    );
                    InvoiceItem item = new InvoiceItem(
                            null,
                            inv.getId(),
                            BillableItemType.CORPORATE_SERVICE,
                            site.getId(),
                            "SEDE CENTRAL.INT.FIB.DEDI.1.65 GB GR 01-31 AGT 2026",
                            BigDecimal.ONE,
                            Money.of(10000000),
                            BigDecimal.ZERO,
                            true,
                            BigDecimal.valueOf(19)
                    );
                    inv.addItem(item);
                    inv.consolidate();
                    return repositorioFactura.saveAndFlush(inv);
                });
        testInvoice.setInvoiceNumber("FESP 51993");
        testInvoice.setPdfPath(null);
        testInvoice = repositorioFactura.saveAndFlush(testInvoice);
    }

    @Test
    @DisplayName("HU-21/HU-22: Generar lote de exportación y verificar descarga de archivo plano")
    void testGenerarLoteYDescargarArchivo() {
        ExportBatch lote = batchService.generateBatch("2026-08", BillingType.F1, BillingCutType.ADVANCE);
        assertNotNull(lote);
        assertEquals(ExportBatchStatus.GENERADO, lote.getStatus());
        assertEquals(1, lote.getTotalInvoices());

        byte[] fileBytes = batchService.getBatchFile(lote.getId());
        assertNotNull(fileBytes);
        assertTrue(fileBytes.length > 0);

        String content = new String(fileBytes);
        assertTrue(content.contains("TIPO_COMPROBANTE"));
        assertTrue(content.contains("900245045"));
        assertTrue(content.contains("11900000.00"));
    }

    @Test
    @DisplayName("HU-23: Confirmar lote con archivo plano desde la carpeta compartida en el servidor")
    void testConfirmarLoteDesdeCarpetaCompartida() throws Exception {
        ExportBatch lote = batchService.generateBatch("2026-08", BillingType.F1, BillingCutType.ADVANCE);

        // Simular archivo plano de Siigo dejado en la carpeta compartida
        String cufeEsperado = "720377e072ca996f78d1f873a16899a51af08fc5ca705c4ebdfa9841c2320214";
        String siigoContent = "NUMERO;VALOR;ESTADO;CUFE;NIT\r\n" +
                "FESP 50776;11900000.00;Envío exitoso;" + cufeEsperado + ";900245045-1\r\n";

        Path planoSiigoPath = sharedFolderPath.resolve("CONSECUTIVO_SIIGO_202608.csv");
        Files.writeString(planoSiigoPath, siigoContent);
        Files.setLastModifiedTime(planoSiigoPath, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 120000));

        // Confirmar lote leyendo de la carpeta compartida
        ExportBatch loteConfirmado = batchService.confirmBatchFromSharedFolder(lote.getId());
        assertNotNull(loteConfirmado);
        assertEquals(ExportBatchStatus.CONFIRMADO, loteConfirmado.getStatus());
        assertNotNull(loteConfirmado.getConfirmedAt());

        // Verificar que la factura en BD se actualizó
        Invoice facturaActualizada = repositorioFactura.findById(testInvoice.getId()).orElseThrow();
        assertEquals("FESP 50776", facturaActualizada.getInvoiceNumber());
        assertEquals(cufeEsperado, facturaActualizada.getCufe());
        assertEquals("Envío exitoso", facturaActualizada.getDispatchStatus());
    }

    @Test
    @DisplayName("HU-24: Escaneo y clasificación automática de PDFs por cliente corporativo (Regex y Fallback)")
    void testClasificacionAutomaticaPdfs() throws Exception {
        // Asignar número oficial a la factura para permitir match
        testInvoice.setInvoiceNumber("FESP 51993");
        repositorioFactura.saveAndFlush(testInvoice);

        // Crear archivo PDF con convención Siigo Nivel 1: F001000000519930000.PDF
        File pdfNivel1 = sharedFolderPath.resolve("F001000000519930000.PDF").toFile();
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(pdfNivel1);
        }
        Files.setLastModifiedTime(pdfNivel1.toPath(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 120000));

        // Ejecutar proceso de escaneo y clasificación
        SiigoPdfImporterService.PdfProcessingSummary summary = pdfImporterService.processSharedFolderPdfs();

        assertEquals(1, summary.totalScanned());
        assertEquals(1, summary.totalMatched());
        assertEquals(0, summary.totalUnmatched());

        SiigoPdfImporterService.PdfMatchResult match = summary.matched().get(0);
        assertEquals(testInvoice.getId(), match.invoiceId());
        assertEquals("900245045-1", match.clientNit());
        assertEquals("ANDINO TELECOMUNICACIONES S.A.S", match.clientBusinessName());
        assertEquals("NOMBRE_ARCHIVO_REGEX", match.matchedBy());

        // Verificar persistencia de ruta en factura
        Invoice facturaConPdf = repositorioFactura.findById(testInvoice.getId()).orElseThrow();
        assertNotNull(facturaConPdf.getPdfPath());
        assertNotEquals(pdfNivel1.getAbsolutePath(), facturaConPdf.getPdfPath());
        assertArrayEquals(Files.readAllBytes(pdfNivel1.toPath()), Files.readAllBytes(Path.of(facturaConPdf.getPdfPath())));
    }
}
