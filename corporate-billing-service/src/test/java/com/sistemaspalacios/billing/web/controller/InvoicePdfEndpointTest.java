package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.service.BillingService;
import com.sistemaspalacios.billing.service.CreditNoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** GET /api/v1/facturas/{id}/pdf: el PDF real de cada factura, para previsualizar o descargar. */
class InvoicePdfEndpointTest {

    @TempDir
    Path storage;

    private MockMvc mockMvc;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        billingService = Mockito.mock(BillingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new InvoiceController(billingService, Mockito.mock(CreditNoteService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Invoice siigoInvoice(String number, InvoiceStatus status, String pdfContent) throws Exception {
        Invoice invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), number,
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 23), BillingType.F1, status);
        invoice.markImportedFromSiigo("F1:" + number);
        if (pdfContent != null) {
            Path file = storage.resolve(invoice.getId() + ".pdf");
            Files.write(file, pdfContent.getBytes(StandardCharsets.ISO_8859_1));
            invoice.setPdfPath(file.toString());
        }
        when(billingService.getInvoiceById(invoice.getId())).thenReturn(invoice);
        return invoice;
    }

    @Test
    @DisplayName("Caso 1: factura con PDF se descarga con nombre F1-51980.pdf y se puede previsualizar inline")
    void invoiceWithPdfCanBeDownloadedAndPreviewed() throws Exception {
        Invoice invoice = siigoInvoice("51980", InvoiceStatus.CONSOLIDATED, "%PDF-1.7 factura 51980");

        mockMvc.perform(get("/api/v1/facturas/" + invoice.getId() + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("F1-51980.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(containsString("factura 51980")));

        mockMvc.perform(get("/api/v1/facturas/" + invoice.getId() + "/pdf").param("disposition", "inline"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("Content-Disposition", containsString("F1-51980.pdf")));
    }

    @Test
    @DisplayName("Caso 2: factura sin PDF recibido: el backend genera el PDF de esa factura con sus datos")
    void invoiceWithoutStoredPdfIsGeneratedByBackend() throws Exception {
        Invoice invoice = siigoInvoice("51981", InvoiceStatus.CONSOLIDATED, null);
        when(billingService.renderInvoicePdf(invoice.getId())).thenReturn("%PDF-1.4 generado 51981".getBytes(StandardCharsets.ISO_8859_1));

        mockMvc.perform(get("/api/v1/facturas/" + invoice.getId() + "/pdf").param("disposition", "inline"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("Content-Disposition", containsString("F1-51981.pdf")))
                .andExpect(content().string(containsString("generado 51981")));
        Mockito.verify(billingService).renderInvoicePdf(invoice.getId());
    }

    @Test
    @DisplayName("Caso 3: con varias facturas del mismo NIT, cada id devuelve exclusivamente su propio PDF")
    void eachInvoiceReturnsItsOwnPdf() throws Exception {
        Invoice a = siigoInvoice("51980", InvoiceStatus.CONSOLIDATED, "%PDF-1.7 contenido A");
        Invoice b = siigoInvoice("51981", InvoiceStatus.CONSOLIDATED, "%PDF-1.7 contenido B");
        Invoice c = siigoInvoice("51982", InvoiceStatus.CONSOLIDATED, "%PDF-1.7 contenido C");

        mockMvc.perform(get("/api/v1/facturas/" + a.getId() + "/pdf")).andExpect(content().string(containsString("contenido A")))
                .andExpect(header().string("Content-Disposition", containsString("F1-51980.pdf")));
        mockMvc.perform(get("/api/v1/facturas/" + b.getId() + "/pdf")).andExpect(content().string(containsString("contenido B")))
                .andExpect(header().string("Content-Disposition", containsString("F1-51981.pdf")));
        mockMvc.perform(get("/api/v1/facturas/" + c.getId() + "/pdf")).andExpect(content().string(containsString("contenido C")))
                .andExpect(header().string("Content-Disposition", containsString("F1-51982.pdf")));
    }

    @Test
    @DisplayName("Caso 5: una factura PAGADA se consulta normalmente")
    void paidInvoicePdfIsAvailable() throws Exception {
        Invoice invoice = siigoInvoice("51983", InvoiceStatus.PAID, "%PDF-1.7 pagada");
        mockMvc.perform(get("/api/v1/facturas/" + invoice.getId() + "/pdf").param("disposition", "inline"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("pagada")));
    }

    @Test
    @DisplayName("Seguridad: si el archivo asociado no es un PDF no se sirve su contenido")
    void nonPdfFileIsNotServed() throws Exception {
        Invoice invoice = siigoInvoice("51984", InvoiceStatus.CONSOLIDATED, "root:x:0:0:root:/root:/bin/bash");
        mockMvc.perform(get("/api/v1/facturas/" + invoice.getId() + "/pdf"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("PDF_ALMACENAMIENTO_ILEGIBLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("root:x"))));
    }
}
