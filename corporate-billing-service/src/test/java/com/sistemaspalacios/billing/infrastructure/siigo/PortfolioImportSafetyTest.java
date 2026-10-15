package com.sistemaspalacios.billing.infrastructure.siigo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.vo.Money;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class PortfolioImportSafetyTest {
    private final SiigoOfficialInvoiceReader reader = new SiigoOfficialInvoiceReader(new ObjectMapper());
    private static final Instant CUTOFF = Instant.parse("2026-10-14T15:00:00Z");

    @Test void separatesOutstandingBalanceFromUnknownInvoiceTotal() throws Exception {
        var row = reader.read("cartera.xlsx", file(69000, false, true), CUTOFF).getFirst();
        assertEquals(Money.ZERO, row.total());
        assertEquals(Money.of("69000"), row.outstandingBalance());
        assertEquals(CUTOFF, row.balanceCutoffAt());
    }
    @Test void retainsExplicitZeroBalance() throws Exception {
        var rows = reader.read("cartera.xlsx", file(0, false, true), CUTOFF);
        assertEquals(1, rows.size());
        assertEquals(Money.ZERO, rows.getFirst().outstandingBalance());
    }
    @Test void rejectsContradictoryDocuments() throws Exception {
        var input = file(69000, true, true);
        assertThrows(IllegalArgumentException.class, () -> reader.read("cartera.xlsx", input, CUTOFF));
    }
    @Test void rejectsMissingIssueDate() throws Exception {
        var input = file(69000, false, false);
        assertThrows(IllegalArgumentException.class, () -> reader.read("cartera.xlsx", input, CUTOFF));
    }
    @Test void requiresExplicitSnapshotTime() throws Exception {
        var input = file(69000, false, true);
        assertThrows(IllegalArgumentException.class, () -> reader.read("cartera.xlsx", input));
    }
    @Test void rejectsMovedBalanceColumnInsteadOfChargingAnotherNumericField() throws Exception {
        try (var book = org.apache.poi.ss.usermodel.WorkbookFactory.create(file(69000, false, true));
             var output = new ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(0).getCell(11).setCellValue("VENDEDOR");
            book.write(output);
            assertThrows(IllegalArgumentException.class, () -> reader.read("cartera.xlsx", new ByteArrayInputStream(output.toByteArray()), CUTOFF));
        }
    }
    private ByteArrayInputStream file(double balance, boolean duplicate, boolean date) throws Exception {
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
            for (int index = 1; index <= (duplicate ? 2 : 1); index++) {
                var row = sheet.createRow(index);
                row.createCell(0).setCellValue("900123456");
                row.createCell(3).setCellValue("F");
                row.createCell(4).setCellValue(1);
                row.createCell(5).setCellValue(123);
                row.createCell(7).setCellValue(20261020);
                row.createCell(11).setCellValue(balance + (index - 1));
                if (date) row.createCell(16).setCellValue(20261001);
            }
            book.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }
}
