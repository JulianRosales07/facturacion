package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.ReceivedPdf;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.IncomingFileHistoryRepository;
import com.sistemaspalacios.billing.repository.ReceivedPdfRepository;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.service.siigo.ScheduledSiigoSyncJob;
import com.sistemaspalacios.billing.web.controller.GlobalExceptionHandler;
import com.sistemaspalacios.billing.web.controller.InvoiceController;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tarea 7: Prueba de punta a punta con el formato real de Siigo.
 * Recorrido completo: carpeta -> base de datos -> copia del PDF -> descarga con formato real.
 */
@SpringBootTest
@ActiveProfiles("postgres")
class OfficialInvoiceSyncE2EIntegrationTest {

    @TempDir
    static Path originDir;

    @TempDir
    static Path copyDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("siigo.carpeta-compartida.path", () -> originDir.toAbsolutePath().toString());
        registry.add("siigo.pdf.local-path", () -> copyDir.toAbsolutePath().toString());
        registry.add("siigo.sync.antiguedad-minima-segundos", () -> "0");
    }

    @Autowired
    private ScheduledSiigoSyncJob syncJob;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private ReceivedPdfRepository receivedPdfRepository;

    @Autowired
    private IncomingFileHistoryRepository historyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private BillingService billingService;

    @Autowired
    private CreditNoteService creditNoteService;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Test
    @DisplayName("Tarea 7: E2E ciclo 1 (ingesta + asociacion), ciclo 2 (idempotencia y saltos), ciclo 3 (asociacion pendiente sin original) y descarga PDF")
    void testEndToEndSiigoRealFormatSyncAndDownload() throws Exception {
        String uniqueConsecutive = Long.toString(Math.abs(new Random().nextLong() % 800000000) + 100000000);
        String testNit = "TEST-" + UUID.randomUUID().toString().substring(0, 8);

        String orphanConsecutive = Long.toString(Math.abs(new Random().nextLong() % 800000000) + 100000000);
        String orphanNit = "TEST-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Preparar fixture Excel a partir de Docs/archivosplanos/movimientocontableFACSEP.xlsx
        Path excelFixturePath = originDir.resolve("movimiento_test.xlsx");
        createExcelFixture(excelFixturePath, uniqueConsecutive, testNit);

        // 2. Preparar PDF para la factura con convención Siigo F001 + consecutivo (11 digitos) + 0000.PDF
        String padConsecutive = String.format("%011d", Long.parseLong(uniqueConsecutive));
        String pdfName = "F001" + padConsecutive + "0000.PDF";
        Path pdf1Path = originDir.resolve(pdfName);
        byte[] pdf1Bytes = generateSamplePdf(pdfName);
        Files.write(pdf1Path, pdf1Bytes);
        String pdf1Hash = computeSha256(pdf1Bytes);

        // 3. Preparar PDF huérfano con consecutivo inexistente
        String orphanPad = String.format("%011d", Long.parseLong(orphanConsecutive));
        String orphanPdfName = "F001" + orphanPad + "0000.PDF";
        Path orphanPdfPath = originDir.resolve(orphanPdfName);
        byte[] orphanPdfBytes = generateSamplePdf(orphanPdfName);
        Files.write(orphanPdfPath, orphanPdfBytes);
        String orphanHash = computeSha256(orphanPdfBytes);

        // 4. Copiar saldos_de_cartera.xlsx
        Path carteraSource = Path.of("Docs/archivosplanos/saldos_de_cartera.xlsx");
        Path carteraInOrigin = originDir.resolve("saldos_de_cartera.xlsx");
        Files.copy(carteraSource, carteraInOrigin);

        // 5. Copiar notas.txt
        Path notasInOrigin = originDir.resolve("notas.txt");
        Files.writeString(notasInOrigin, "Notas de prueba de sincronizacion");

        List<String> hashesToCleanup = new ArrayList<>(List.of(pdf1Hash, orphanHash));
        List<String> filesToCleanup = new ArrayList<>(List.of("movimiento_test.xlsx", "saldos_de_cartera.xlsx", "notas.txt", "orphan_invoice.json"));
        List<String> keysToCleanup = new ArrayList<>(List.of("F1:" + uniqueConsecutive, "F1:" + orphanConsecutive));
        List<String> nitsToCleanup = new ArrayList<>(List.of(testNit, orphanNit));

        try {
            // ==========================================
            // CICLO 1: Primera sincronización completa
            // ==========================================
            var status1 = syncJob.triggerManualSync();
            assertNotNull(status1);

            // Verificar factura creada en base de datos
            var invoiceOpt = repositorioFactura.findByExternalDocumentKey("F1:" + uniqueConsecutive);
            assertTrue(invoiceOpt.isPresent(), "La factura F1:" + uniqueConsecutive + " debe existir en base de datos");
            Invoice invoice = invoiceOpt.get();

            assertEquals(testNit, invoice.getClientNit());
            assertEquals(Money.of(238000), invoice.getTotalAmount());
            assertFalse(invoice.getItems().isEmpty(), "La factura debe contener los ítems del movimiento contable");
            assertNotNull(invoice.getPdfPath(), "La factura debe tener ruta_pdf asociada");
            assertTrue(invoice.getPdfPath().startsWith(copyDir.toAbsolutePath().toString()),
                    "La ruta del PDF debe apuntar a la carpeta local de copias");

            // Contenido byte a byte idéntico
            assertArrayEquals(pdf1Bytes, Files.readAllBytes(Path.of(invoice.getPdfPath())),
                    "El PDF asociado debe ser idéntico byte a byte al original");

            // Los originales siguen en la carpeta de origen intactos
            assertTrue(Files.exists(pdf1Path), "El PDF original debe seguir en la carpeta de origen");
            assertArrayEquals(pdf1Bytes, Files.readAllBytes(pdf1Path));
            assertTrue(Files.exists(orphanPdfPath));
            assertTrue(Files.exists(excelFixturePath));
            assertTrue(Files.exists(carteraInOrigin));
            assertTrue(Files.exists(notasInOrigin));

            // PDF huérfano queda PENDIENTE con copia en _pendientes
            var orphanReceivedOpt = receivedPdfRepository.findBySha256(orphanHash);
            assertTrue(orphanReceivedOpt.isPresent(), "El PDF huérfano debe estar registrado en pdf_recibido");
            ReceivedPdf orphanReceived = orphanReceivedOpt.get();
            assertEquals(ReceivedPdf.STATUS_PENDING, orphanReceived.getStatus());
            assertNotNull(orphanReceived.getLocalPath());
            assertTrue(orphanReceived.getLocalPath().contains("_pendientes"));
            assertTrue(Files.exists(Path.of(orphanReceived.getLocalPath())),
                    "La copia del PDF huérfano debe existir en la carpeta _pendientes");

            // Historial con filas esperadas
            var historyRows = historyRepository.findAll();
            assertTrue(historyRows.stream().anyMatch(h -> h.getFilename().equals("movimiento_test.xlsx") && "IMPORTADO".equals(h.getStatus())));
            assertTrue(historyRows.stream().anyMatch(h -> h.getFilename().equals("saldos_de_cartera.xlsx") && "RECHAZADO".equals(h.getStatus())),
                    "Un corte sin fecha de generación no se aplica ni se anuncia como verificado");
            assertTrue(historyRows.stream().anyMatch(h -> h.getFilename().equals("notas.txt") && "OMITIDO".equals(h.getStatus())));

            long historyCountCiclo1 = historyRows.size();

            // ==========================================
            // CICLO 2: Sin cambios (idempotencia y saltos)
            // ==========================================
            var status2 = syncJob.triggerManualSync();
            assertNotNull(status2);

            // Ni facturas duplicadas
            long invoiceCount = repositorioFactura.findAll().stream()
                    .filter(i -> ("F1:" + uniqueConsecutive).equals(i.getExternalDocumentKey()))
                    .count();
            assertEquals(1, invoiceCount, "No deben crearse facturas duplicadas");

            // Ni filas nuevas de historial para los archivos ya procesados
            long historyCountCiclo2 = historyRepository.findAll().size();
            assertEquals(historyCountCiclo1, historyCountCiclo2, "Los archivos ya procesados deben saltarse sin generar nuevas filas de historial");

            // ==========================================
            // CICLO 3: Reasociación de PDF pendiente sin original
            // ==========================================
            // Borrar el PDF huérfano del origen para demostrar que se asocia desde la copia local
            Files.deleteIfExists(orphanPdfPath);
            assertFalse(Files.exists(orphanPdfPath), "El PDF original huérfano fue borrado del origen");

            // Depositar JSON con los datos de la factura para ese consecutivo huérfano
            String orphanJson = """
                    [
                      {
                        "nit": "%s",
                        "businessName": "Cliente Huerfano Test",
                        "billingType": "F1",
                        "invoiceNumber": "%s",
                        "issueDate": "2026-09-01",
                        "dueDate": "2026-09-30",
                        "cufe": null,
                        "total": 119000.00,
                        "items": [
                          {
                            "code": "SERV-1",
                            "description": "Servicio pendiente",
                            "quantity": 1,
                            "subtotal": 100000.00,
                            "iva": 19000.00
                          }
                        ]
                      }
                    ]
                    """.formatted(orphanNit, orphanConsecutive);

            Path orphanJsonPath = originDir.resolve("orphan_invoice.json");
            Files.writeString(orphanJsonPath, orphanJson);

            // Disparar sincronización
            var status3 = syncJob.triggerManualSync();
            assertNotNull(status3);

            // La factura huérfana debe existir
            var orphanInvoiceOpt = repositorioFactura.findByExternalDocumentKey("F1:" + orphanConsecutive);
            assertTrue(orphanInvoiceOpt.isPresent(), "La factura para orphanConsecutive debió crearse");
            Invoice orphanInvoice = orphanInvoiceOpt.get();

            // La fila en pdf_recibido pasa a ASOCIADO
            var orphanUpdatedOpt = receivedPdfRepository.findBySha256(orphanHash);
            assertTrue(orphanUpdatedOpt.isPresent());
            assertEquals(ReceivedPdf.STATUS_ASSOCIATED, orphanUpdatedOpt.get().getStatus(),
                    "El PDF huérfano debió pasar a estado ASOCIADO usando su copia local");
            assertEquals(orphanInvoice.getId(), orphanUpdatedOpt.get().getInvoiceId());

            assertNotNull(orphanInvoice.getPdfPath());
            assertArrayEquals(orphanPdfBytes, Files.readAllBytes(Path.of(orphanInvoice.getPdfPath())),
                    "La factura huérfana debe tener asociado el PDF idéntico byte a byte al generado originalmente");

            // ==========================================
            // DESCARGA: MockMvc GET /api/v1/facturas/{id}/pdf
            // ==========================================
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InvoiceController(billingService, creditNoteService))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();

            mockMvc.perform(get("/api/v1/facturas/" + invoice.getId() + "/pdf"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                    .andExpect(header().string("Content-Disposition", Matchers.containsString("attachment")))
                    .andExpect(header().string("Content-Disposition", Matchers.containsString("F1-" + uniqueConsecutive + ".pdf")))
                    .andExpect(content().bytes(pdf1Bytes));

        } finally {
            // Limpieza con SQL directo para respetar llaves foráneas y evitar desvinculaciones erróneas de JPA
            try (java.sql.Connection c = dataSource.getConnection()) {
                c.setAutoCommit(true);
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM pdf_recibido WHERE factura_id IN (SELECT id FROM factura WHERE clave_documento_externo = ?)")) {
                    for (String key : keysToCleanup) {
                        ps.setString(1, key);
                        ps.executeUpdate();
                    }
                }
                try (java.sql.PreparedStatement ps = c.prepareStatement("DELETE FROM pdf_recibido WHERE sha256 = ?")) {
                    for (String hash : hashesToCleanup) {
                        ps.setString(1, hash);
                        ps.executeUpdate();
                    }
                }
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM item_facturable WHERE factura_id IN (SELECT id FROM factura WHERE clave_documento_externo = ?)")) {
                    for (String key : keysToCleanup) {
                        ps.setString(1, key);
                        ps.executeUpdate();
                    }
                }
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM factura WHERE clave_documento_externo = ?")) {
                    for (String key : keysToCleanup) {
                        ps.setString(1, key);
                        ps.executeUpdate();
                    }
                }
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM cliente_corporativo WHERE nit = ?")) {
                    for (String nit : nitsToCleanup) {
                        ps.setString(1, nit);
                        ps.executeUpdate();
                    }
                }
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM historial_recepcion_archivo WHERE sha256 = ?")) {
                    for (String hash : hashesToCleanup) {
                        ps.setString(1, hash);
                        ps.executeUpdate();
                    }
                }
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM historial_recepcion_archivo WHERE nombre_archivo = ?")) {
                    for (String file : filesToCleanup) {
                        ps.setString(1, file);
                        ps.executeUpdate();
                    }
                }
            } catch (Exception ex) {
                // Log and continue cleanup of files
                System.err.println("Advertencia en limpieza de base de datos de test: " + ex.getMessage());
            }
        }
    }

    private void createExcelFixture(Path destination, String consecutivo, String testNit) throws Exception {
        Path sourcePath = Path.of("Docs/archivosplanos/movimientocontableFACSEP.xlsx");
        try (InputStream in = Files.newInputStream(sourcePath);
             Workbook sourceWb = WorkbookFactory.create(in);
             Workbook destWb = new XSSFWorkbook()) {

            Sheet sourceSheet = sourceWb.getSheetAt(0);
            Sheet destSheet = destWb.createSheet("Movimiento");

            int headerIndex = -1;
            for (int i = 0; i <= Math.min(30, sourceSheet.getLastRowNum()); i++) {
                Row row = sourceSheet.getRow(i);
                if (row != null) {
                    Cell c0 = row.getCell(0);
                    Cell c3 = row.getCell(3);
                    Cell c15 = row.getCell(15);
                    if (c0 != null && c0.toString().contains("TIPO DE COMPROBANTE") &&
                        c3 != null && c3.toString().contains("CUENTA CONTABLE") &&
                        c15 != null && c15.toString().equals("NIT")) {
                        headerIndex = i;
                        break;
                    }
                }
            }

            if (headerIndex < 0) {
                throw new IllegalStateException("No se pudo localizar fila de encabezados en el movimiento contable");
            }

            int destRowIdx = 0;
            // Copiar filas hasta el encabezado
            for (int i = 0; i <= headerIndex; i++) {
                Row srcRow = sourceSheet.getRow(i);
                if (srcRow != null) {
                    Row destRow = destSheet.createRow(destRowIdx++);
                    copyRow(srcRow, destRow);
                }
            }

            int copiedRows = 0;
            // Copiar solo filas del documento 51753
            for (int i = headerIndex + 1; i <= sourceSheet.getLastRowNum(); i++) {
                Row srcRow = sourceSheet.getRow(i);
                if (srcRow != null) {
                    String compType = getCellString(srcRow, 0);
                    String code = getCellString(srcRow, 1);
                    String num = getCellString(srcRow, 2);
                    if ("F".equalsIgnoreCase(compType) && "1".equals(code) && "51753".equals(num)) {
                        Row destRow = destSheet.createRow(destRowIdx++);
                        copyRow(srcRow, destRow);
                        // Reemplazar consecutivo en col 2
                        Cell cell2 = destRow.getCell(2);
                        if (cell2 == null) cell2 = destRow.createCell(2);
                        cell2.setCellValue(consecutivo);

                        // Reemplazar NIT en col 15
                        Cell cell15 = destRow.getCell(15);
                        if (cell15 == null) cell15 = destRow.createCell(15);
                        cell15.setCellValue(testNit);
                        copiedRows++;
                    }
                }
            }

            if (copiedRows == 0) {
                throw new IllegalStateException("No se encontraron filas para el documento F1 51753 en movimientocontableFACSEP.xlsx");
            }

            try (OutputStream out = Files.newOutputStream(destination)) {
                destWb.write(out);
            }
        }
    }

    private String getCellString(Row row, int col) {
        if (row == null || row.getCell(col) == null) return "";
        Cell cell = row.getCell(col);
        return cell.getCellType() == CellType.NUMERIC
                ? java.math.BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString()
                : new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
    }

    private void copyRow(Row src, Row dest) {
        for (int j = 0; j < src.getLastCellNum(); j++) {
            Cell srcCell = src.getCell(j);
            if (srcCell != null) {
                Cell destCell = dest.createCell(j);
                switch (srcCell.getCellType()) {
                    case STRING -> destCell.setCellValue(srcCell.getStringCellValue());
                    case NUMERIC -> destCell.setCellValue(srcCell.getNumericCellValue());
                    case BOOLEAN -> destCell.setCellValue(srcCell.getBooleanCellValue());
                    case FORMULA -> destCell.setCellValue(srcCell.getCellFormula());
                    default -> destCell.setCellValue(srcCell.toString());
                }
            }
        }
    }

    /**
     * PDF de prueba único por nombre. Dos PDF en blanco generados seguidos pueden salir byte a
     * byte iguales (mismo SHA-256), y entonces el "huérfano" se reconoce, con razón, como el PDF
     * ya asociado: la prueba fallaba de forma intermitente.
     */
    private byte[] generateSamplePdf(String marker) throws IOException {
        try (var doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.getDocumentInformation().setTitle(marker);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private String computeSha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
