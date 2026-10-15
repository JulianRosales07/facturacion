package com.sistemaspalacios.billing.infrastructure.siigo;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.domain.model.ExportBatchStatus;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.vo.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SiigoFlatFileExporterTest {

    private final SiigoFlatFileExporter exporter = new SiigoFlatFileExporter();

    @Test
    @DisplayName("HU-21/HU-22: Exportador genera layout F1 balanceado en doble partida y sin retenciones")
    void testExportBatchF1BalancedLayout() throws Exception {
        UUID periodId = UUID.randomUUID();
        CorporateClient client = new CorporateClient(
                UUID.randomUUID(),
                "1088970617-6",
                "CRISTIAN FERNANDO BRAVO MUÑOZ",
                "LA CRUZ",
                "crisfe8609@gmail.com",
                "3135963672",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "LA CRUZ",
                null
        );

        Invoice invoice = new Invoice(
                UUID.randomUUID(),
                client.getId(),
                periodId,
                BillingType.F1,
                client,
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 9, 15)
        );

        invoice.setInvoiceNumber("FESP 51993");

        // Add 2 items (Invoice automatically recalculates totals upon addItem)
        InvoiceItem item1 = new InvoiceItem(
                null,
                invoice.getId(),
                com.sistemaspalacios.billing.domain.model.BillableItemType.CORPORATE_SERVICE,
                UUID.randomUUID(),
                "SEDE 1.INT.FIB.DEDI.1.65 GB GR 01-31 AGT 2026",
                BigDecimal.ONE,
                Money.of(2000000),
                BigDecimal.ZERO,
                true,
                BigDecimal.valueOf(19)
        );

        InvoiceItem item2 = new InvoiceItem(
                null,
                invoice.getId(),
                com.sistemaspalacios.billing.domain.model.BillableItemType.CORPORATE_SERVICE,
                UUID.randomUUID(),
                "SEDE 2.INT.FIB.DEDI.1.65 GB GR 01-31 AGT 2026",
                BigDecimal.ONE,
                Money.of(1000000),
                BigDecimal.ZERO,
                true,
                BigDecimal.valueOf(19)
        );

        invoice.addItem(item1);
        invoice.addItem(item2);

        ExportBatch batch = new ExportBatch(
                UUID.randomUUID(),
                periodId,
                BillingType.F1,
                BillingCutType.ADVANCE.name(),
                "LOTE_F1_2026-08.txt",
                1,
                ExportBatchStatus.GENERADO
        );

        byte[] bytes = exporter.exportBatch(batch, List.of(invoice));
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.ISO_8859_1));
        String header = reader.readLine();
        assertNotNull(header);
        assertTrue(header.contains("TIPO_COMPROBANTE"));
        assertTrue(header.contains("CODIGO_COMPROBANTE"));

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        int rowCount = 0;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            rowCount++;
            String[] cols = line.split(SiigoFlatFileExporter.DELIMITER, -1);
            assertTrue(cols.length >= 24, "Cada fila debe tener al menos 24 columnas Siigo");

            assertEquals("F", cols[0]); // Tipo comprobante
            assertEquals("1", cols[1]); // F1 = código 1

            String debOrCred = cols[4];
            BigDecimal val = new BigDecimal(cols[5]);

            if ("D".equals(debOrCred)) {
                totalDebits = totalDebits.add(val);
            } else if ("C".equals(debOrCred)) {
                totalCredits = totalCredits.add(val);
            }
        }

        assertEquals(4, rowCount, "Debe haber 4 filas contables: 2 ingresos + 1 IVA + 1 Cartera");
        assertEquals(0, totalDebits.compareTo(totalCredits), "Partida doble estricta: Total Débitos debe ser igual a Total Créditos");
        assertEquals(0, new BigDecimal("3570000.00").compareTo(totalDebits), "Débito a cartera debe ser exactamente el valor oficial (3,570,000)");

        // Regla 9: Retenciones NO van en el plano Siigo
        assertFalse(new String(bytes).contains("3420000.00"), "El neto a pagar no debe reemplazar al valor oficial en el plano");
        assertFalse(new String(bytes).contains("150000.00"), "Las retenciones no deben aparecer en el archivo plano");
    }
}
