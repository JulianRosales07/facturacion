package com.sistemaspalacios.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.infrastructure.siigo.PdfBoxMetadataExtractor;
import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor.PdfExtractedMetadata;
import com.sistemaspalacios.billing.service.dto.FacturaSpPagosDto;
import com.sistemaspalacios.billing.service.dto.OfficialInvoiceData;
import com.sistemaspalacios.billing.service.siigo.OfficialInvoiceReader;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
public class PruebaLecturaCarpetaServerTest {
    @org.junit.jupiter.api.io.TempDir Path outputDirectory;
    @Autowired private OfficialInvoiceDetailService detailService;

    @Autowired
    private OfficialInvoiceReader officialReader;

    @Autowired
    private OfficialInvoiceImportService importer;

    @Autowired
    private SpPagosQueryService spPagosService;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private PdfBoxMetadataExtractor pdfMetadataExtractor;

    @Autowired
    private com.sistemaspalacios.billing.repository.RepositorioCliente repositorioCliente;

    @Test
    @DisplayName("Prueba oficial: Leer Docs/server (saldos_de_cartera.xlsx + F001000000519930000.PDF) -> JSON SP Pagos")
    void testLecturaCarpetaServerYGeneracionJson() throws Exception {
        Path serverDir = Paths.get("Docs/server").toAbsolutePath();
        File excelFile = serverDir.resolve("saldos_de_cartera.xlsx").toFile();
        File pdfFile = serverDir.resolve("F001000000519930000.PDF").toFile();

        assertTrue(excelFile.exists(), "Debe existir saldos_de_cartera.xlsx en Docs/server");
        assertTrue(pdfFile.exists(), "Debe existir F001000000519930000.PDF en Docs/server");

        System.out.println("================================================================================");
        System.out.println(">>> 1. ARCHIVOS ENCONTRADOS EN " + serverDir);
        System.out.println("  - Excel: " + excelFile.getName() + " (" + excelFile.length() + " bytes)");
        System.out.println("  - PDF:   " + pdfFile.getName() + " (" + pdfFile.length() + " bytes)");
        System.out.println("================================================================================");

        // 1. Leer saldos_de_cartera.xlsx
        List<OfficialInvoiceData> records;
        try (FileInputStream fis = new FileInputStream(excelFile)) {
            records = officialReader.read(excelFile.getName(), fis, java.time.Instant.parse("2026-10-14T15:00:00Z"));
        }

        System.out.println(">>> 2. FACTURAS LEÍDAS DE saldos_de_cartera.xlsx: " + records.size());
        assertFalse(records.isEmpty());

        // 2. Extraer metadatos del PDF mediante Apache PDFBox
        PdfExtractedMetadata pdfMeta = pdfMetadataExtractor.extractMetadata(pdfFile);
        System.out.println(">>> 3. METADATOS EXTRAÍDOS DEL PDF (" + pdfFile.getName() + "):");
        System.out.println("  - Factura:      " + pdfMeta.invoiceNumber());
        System.out.println("  - NIT Cliente:  " + pdfMeta.clientNit());
        System.out.println("  - Razón Social: " + pdfMeta.clientBusinessName());
        System.out.println("  - Dirección:    " + pdfMeta.clientAddress());
        System.out.println("  - Email:        " + pdfMeta.clientEmail());
        System.out.println("  - CUFE:         " + pdfMeta.cufe());
        System.out.println("  - Total:        $" + pdfMeta.totalAmount());
        System.out.println("  - Emisión:      " + pdfMeta.issueDate());
        System.out.println("  - Vencimiento:  " + pdfMeta.dueDate());
        System.out.println("================================================================================");

        // 3. Ubicar la factura 51993 en los registros contables del Excel
        Optional<OfficialInvoiceData> invoice51993Opt = records.stream()
                .filter(r -> "51993".equals(r.invoiceNumber()))
                .findFirst();

        assertTrue(invoice51993Opt.isPresent(), "La factura 51993 debe estar en saldos_de_cartera.xlsx");
        OfficialInvoiceData invoiceData = invoice51993Opt.get();

        System.out.println(">>> 4. DATOS CONTABLES DE LA FACTURA EN EXCEL:");
        System.out.println("  - Número:        " + invoiceData.invoiceNumber());
        System.out.println("  - NIT:           " + invoiceData.nit());
        System.out.println("  - Razón Social:  " + invoiceData.businessName());
        System.out.println("  - Tipo:          " + invoiceData.billingType());
        System.out.println("  - Emisión:       " + invoiceData.issueDate());
        System.out.println("  - Vencimiento:   " + invoiceData.dueDate());
        System.out.println("  - Saldo Total:   $" + invoiceData.total());
        System.out.println("================================================================================");

        // 4. Importar factura oficial al sistema
        Invoice importedInvoice = importer.importDocument(invoiceData);
        assertNotNull(importedInvoice);
        assertFalse(importedInvoice.isPayable(), "Un saldo sin detalle todavía no es cobrable");
        assertEquals(OfficialInvoiceDetailService.Outcome.APLICADO,
                detailService.applyPdfDetail(importedInvoice, pdfMeta, pdfFile.getName()).outcome());

        // 5. Enriquecer la factura con los datos fiscales del PDF
        importedInvoice.setCufe(pdfMeta.cufe());
        importedInvoice.setPdfPath(pdfFile.getAbsolutePath());
        if (pdfMeta.clientAddress() != null && !pdfMeta.clientAddress().isBlank()) {
            importedInvoice.setClientTaxAddress(pdfMeta.clientAddress());
        }
        if (pdfMeta.clientEmail() != null && !pdfMeta.clientEmail().isBlank()) {
            importedInvoice.setClientBillingEmail(pdfMeta.clientEmail());
        }
        if (pdfMeta.clientBusinessName() != null && !pdfMeta.clientBusinessName().isBlank()) {
            importedInvoice.setClientBusinessName(pdfMeta.clientBusinessName());
            repositorioCliente.findById(importedInvoice.getClientId()).ifPresent(c -> {
                c.setBusinessName(pdfMeta.clientBusinessName());
                repositorioCliente.saveAndFlush(c);
            });
        }
        repositorioFactura.saveAndFlush(importedInvoice);

        // 6. Consultar DTO preparado para SP Pagos
        FacturaSpPagosDto spPagosDto = spPagosService.getInvoiceForPayment(importedInvoice.getId());
        assertNotNull(spPagosDto);

        // 7. Generar JSON Formateado
        ObjectMapper jsonMapper = new ObjectMapper();
        jsonMapper.registerModule(new JavaTimeModule());
        jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
        jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String jsonGenerado = jsonMapper.writeValueAsString(spPagosDto);

        System.out.println(">>> 5. JSON GENERADO Y ESTRUCTURADO PARA SP PAGOS:");
        System.out.println(jsonGenerado);
        System.out.println("================================================================================");

        // 8. Guardar el archivo JSON en Docs/server
        Path jsonOutputFile = outputDirectory.resolve("factura_51993_sp_pagos.json");
        Files.writeString(jsonOutputFile, jsonGenerado);
        System.out.println(">>> Archivo JSON guardado con éxito en: " + jsonOutputFile);
        System.out.println("================================================================================");

        assertEquals("51993", spPagosDto.numeroFactura());
        assertEquals("FESP", spPagosDto.prefijo());
        assertEquals(pdfMeta.cufe(), spPagosDto.cufe());
        assertTrue(spPagosDto.pagable());
        assertEquals(new java.math.BigDecimal("5694150.00"), spPagosDto.importes().saldoPendiente());
    }

    @Test
    @DisplayName("Prueba con OTRO NIT del Excel saldos_de_cartera.xlsx -> JSON SP Pagos")
    void testLecturaOtroNitDelExcel() throws Exception {
        Path serverDir = Paths.get("Docs/server").toAbsolutePath();
        File excelFile = serverDir.resolve("saldos_de_cartera.xlsx").toFile();
        assertTrue(excelFile.exists());

        // 1. Leer todas las facturas del Excel
        List<OfficialInvoiceData> records;
        try (FileInputStream fis = new FileInputStream(excelFile)) {
            records = officialReader.read(excelFile.getName(), fis, java.time.Instant.parse("2026-10-14T15:00:00Z"));
        }

        // 2. Filtrar un NIT diferente al anterior (1088970617)
        List<OfficialInvoiceData> otrosNits = records.stream()
                .filter(r -> r.nit() != null && !r.nit().equals("1088970617") && r.outstandingBalance().isPositive())
                .toList();

        assertFalse(otrosNits.isEmpty(), "Deben existir otros NITs en el archivo Excel");

        System.out.println("================================================================================");
        System.out.println(">>> EJEMPLOS DE OTROS NITS ENCONTRADOS EN saldos_de_cartera.xlsx:");
        for (int i = 0; i < Math.min(5, otrosNits.size()); i++) {
            OfficialInvoiceData sample = otrosNits.get(i);
            System.out.println("  [" + (i + 1) + "] Factura: " + sample.invoiceNumber() 
                    + " | NIT: " + sample.nit() 
                    + " | Tipo: " + sample.billingType()
                    + " | Total: $" + sample.total()
                    + " | Vence: " + sample.dueDate());
        }
        System.out.println("================================================================================");

        // Seleccionar el primer registro de la lista con NIT diferente
        OfficialInvoiceData otroRegistro = otrosNits.get(0);
        System.out.println(">>> FACTURA SELECCIONADA PARA LA PRUEBA:");
        System.out.println("  - Factura N°:   " + otroRegistro.invoiceNumber());
        System.out.println("  - NIT Cliente:  " + otroRegistro.nit());
        System.out.println("  - Tipo:         " + otroRegistro.billingType());
        System.out.println("  - Emisión:      " + otroRegistro.issueDate());
        System.out.println("  - Vencimiento:  " + otroRegistro.dueDate());
        System.out.println("  - Saldo Total:  $" + otroRegistro.total());
        System.out.println("================================================================================");

        // 3. Importar la factura al dominio contable oficial
        Invoice facturaImportada = importer.importDocument(otroRegistro);
        assertNotNull(facturaImportada);

        // 4. Si el cliente tiene un nombre o asignamos datos complementarios
        facturaImportada.setClientTaxAddress("CENTRO COMERCIAL Y DE NEGOCIOS CALLE 18 # 25-10");
        facturaImportada.setClientBillingEmail("contabilidad." + otroRegistro.nit() + "@empresa.com");
        repositorioFactura.saveAndFlush(facturaImportada);

        // 5. Consultar DTO preparado para SP Pagos
        FacturaSpPagosDto spPagosDto = spPagosService.getInvoiceForPayment(facturaImportada.getId());
        assertNotNull(spPagosDto);

        // 6. Serializar a JSON Formateado
        ObjectMapper jsonMapper = new ObjectMapper();
        jsonMapper.registerModule(new JavaTimeModule());
        jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
        jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String jsonGenerado = jsonMapper.writeValueAsString(spPagosDto);

        System.out.println(">>> JSON GENERADO PARA EL NUEVO NIT (" + otroRegistro.nit() + ") DESTINADO A SP PAGOS:");
        System.out.println(jsonGenerado);
        System.out.println("================================================================================");

        // 7. Guardar el archivo JSON en Docs/server
        Path jsonOutputFile = outputDirectory.resolve("factura_nit_" + otroRegistro.nit() + "_sp_pagos.json");
        Files.writeString(jsonOutputFile, jsonGenerado);
        System.out.println(">>> Archivo JSON guardado exitosamente en: " + jsonOutputFile);
        System.out.println("================================================================================");

        assertEquals(otroRegistro.invoiceNumber(), spPagosDto.numeroFactura());
        assertEquals(otroRegistro.nit(), spPagosDto.cliente().nit());
        assertEquals(otroRegistro.outstandingBalance().getAmount(), spPagosDto.importes().saldoPendiente());
        assertFalse(spPagosDto.pagable(), "Sin detalle oficial no se habilita el cobro");
    }
}
