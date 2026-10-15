package com.sistemaspalacios.billing.infrastructure.siigo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.service.dto.OfficialInvoiceData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SiigoOfficialInvoiceReaderTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SiigoOfficialInvoiceReader reader = new SiigoOfficialInvoiceReader(mapper);

    @Test
    @DisplayName("Lee facturas F1 y F20 directamente desde el archivo real saldos_de_cartera.xlsx de Docs/server")
    void testReadsInvoicesFromSaldosDeCartera() throws Exception {
        File file = new File("Docs/server/saldos_de_cartera.xlsx");
        if (!file.exists()) {
            file = new File("Docs/Ejemplo carpeta siggo/saldos_de_cartera.xlsx");
        }
        assertTrue(file.exists(), "El archivo saldos_de_cartera.xlsx debe existir");

        try (var stream = new FileInputStream(file)) {
            List<OfficialInvoiceData> invoices = reader.read(file.getName(), stream);
            assertNotNull(invoices);
            assertFalse(invoices.isEmpty(), "Debe extraer facturas desde saldos_de_cartera.xlsx");

            // Buscar la factura real del ejemplo: 51993 (F1)
            var inv51993Opt = invoices.stream()
                    .filter(i -> "51993".equals(i.invoiceNumber()))
                    .findFirst();

            assertTrue(inv51993Opt.isPresent(), "Debe encontrar la factura 51993");
            OfficialInvoiceData inv51993 = inv51993Opt.get();
            assertEquals("1088970617", inv51993.nit());
            assertEquals(BillingType.F1, inv51993.billingType());
            assertEquals(Money.of("5694150.00"), inv51993.total());
            assertNotNull(inv51993.issueDate());
            assertEquals("2026-09-10", inv51993.issueDate().toString());
            assertNotNull(inv51993.dueDate());
            assertEquals("2026-09-25", inv51993.dueDate().toString());
            assertFalse(inv51993.items().isEmpty());
            assertEquals(inv51993.total(), inv51993.items().getFirst().subtotal());
        }
    }
}
