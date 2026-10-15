package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.*;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.service.dto.OfficialInvoiceData;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"postgres", "test"})
@Transactional
class RealCollectionImportIntegrationTest {
    @Autowired IncomingFileIntakeService intake;
    @Autowired OfficialInvoiceImportService importer;
    @Autowired IncomingFileAuditService audit;
    @Autowired PaymentService payments;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("repositorioFactura") RepositorioFactura invoices;

    @Test void importsPortfolioThenOfficialDetailAndAppliesAbonoAndExplicitZero() throws Exception {
        String number = Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1000000000));
        Instant cutoff = Instant.parse("2026-10-12T15:00:00Z");
        var first = intake.receive("cartera.xlsx", workbook(number, 119000), "MANUAL", cutoff);
        Invoice invoice = invoices.findById(first.invoiceIds().getFirst()).orElseThrow();
        assertEquals(Money.ZERO, invoice.getTotalAmount(), "No se inventa el total original desde un saldo");
        assertEquals(Money.of("119000"), invoice.getPendingBalance());
        assertFalse(invoice.isPayable());

        var official = new OfficialInvoiceData("900123456", "Prueba cartera", BillingType.F1, number,
                LocalDate.of(2026,10,1), LocalDate.of(2026,10,20), null, Money.of("119000"),
                List.of(new OfficialInvoiceData.Item("INTERNET", "Servicio internet", BigDecimal.ONE, Money.of("100000"), Money.of("19000"))));
        importer.importDocument(official);
        assertTrue(invoice.isPayable());
        var abono = intake.receive("cartera.xlsx", workbook(number, 69000), "MANUAL", cutoff.plusSeconds(60));
        assertEquals(1, abono.updated());
        assertEquals(invoice.getId(), abono.invoiceIds().getFirst());
        assertEquals(Money.of("119000"), invoice.getTotalAmount());
        assertEquals(Money.of("19000"), invoice.getIvaAmount());
        assertEquals(Money.of("69000"), invoice.getPendingBalance());
        var repeated = intake.receive("cartera.xlsx", workbook(number, 69000), "MANUAL", cutoff.plusSeconds(60));
        assertEquals(List.of(invoice.getId()), repeated.invoiceIds());

        intake.receive("cartera.xlsx", workbook(number, 0), "MANUAL", cutoff.plusSeconds(120));
        assertEquals(Money.ZERO, invoice.getPendingBalance());
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        assertFalse(invoice.isPayable());
    }

    @Test void rejectsOldCutWithoutChangingKnownBalance() throws Exception {
        String number = Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1000000000));
        Instant cutoff = Instant.parse("2026-10-12T15:00:00Z");
        var result = intake.receive("cartera.xlsx", workbook(number, 69000), "MANUAL", cutoff);
        assertThrows(IllegalArgumentException.class,
                () -> intake.receive("cartera.xlsx", workbook(number, 119000), "MANUAL", cutoff.minusSeconds(60)));
        assertEquals(Money.of("69000"), invoices.findById(result.invoiceIds().getFirst()).orElseThrow().getPendingBalance());
    }

    @Test void panelMultipartContractUpdatesBalancesAndReportsRepeats() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.sistemaspalacios.billing.web.controller.OfficialInvoiceImportController(intake, audit)).build();
        String number = Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1000000000));
        var file = new org.springframework.mock.web.MockMultipartFile("archivo", "cartera.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook(number, 69000));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/siigo/facturas/importar-detallado")
                        .file(file).param("fechaCorte", "2026-10-12T15:00:00Z"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.created").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.importedBalance").value(69000));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/siigo/facturas/importar-detallado")
                        .file(file).param("fechaCorte", "2026-10-12T15:00:00Z"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.repeated").value(1));
    }

    @Test void directConfirmationCannotBypassPendingOfficialDetail() throws Exception {
        String number = Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1000000000));
        var result = intake.receive("cartera.xlsx", workbook(number, 69000), "MANUAL", Instant.parse("2026-10-12T15:00:00Z"));
        assertThrows(PaymentConflictException.class, () -> payments.confirmPayment(new PaymentService.PaymentConfirmationCommand(
                "900123456", "UNVERIFIED-" + UUID.randomUUID(), null, result.invoiceIds(), new BigDecimal("69000"), LocalDate.now(), "PSE", "BANCO", null, null, null, null)));
    }

    private byte[] workbook(String number, double balance) throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet();
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("IDENTIFICACIÓN");
            header.createCell(3).setCellValue("TIPO DE COMPROBANTE");
            header.createCell(4).setCellValue("CÓDIGO COMPROBANTE");
            header.createCell(5).setCellValue("NÚMERO DE DOCUMENTO");
            header.createCell(7).setCellValue("FECHA VENCIMIENTO");
            header.createCell(11).setCellValue("SALDO");
            header.createCell(16).setCellValue("FECHA DOCUMENTO");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("900123456");
            row.createCell(3).setCellValue("F");
            row.createCell(4).setCellValue(1);
            row.createCell(5).setCellValue(number);
            row.createCell(7).setCellValue(20261020);
            row.createCell(11).setCellValue(balance);
            row.createCell(16).setCellValue(20261001);
            book.write(out);
            return out.toByteArray();
        }
    }
}
