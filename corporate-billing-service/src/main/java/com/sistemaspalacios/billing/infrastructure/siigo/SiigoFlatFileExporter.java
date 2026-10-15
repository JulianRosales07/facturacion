package com.sistemaspalacios.billing.infrastructure.siigo;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.service.AccountingEntryGenerator;
import com.sistemaspalacios.billing.domain.vo.AccountingEntryLine;
import com.sistemaspalacios.billing.service.siigo.EmisorFacturaOficial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Generates the official Siigo movement flat file (asiento de doble partida)
 * strictly without withholdings, according to Section 9 of the specifications.
 * Implements OfficialInvoiceEmitter / EmisorFacturaOficial.
 */
@Primary
@Component
public class SiigoFlatFileExporter implements EmisorFacturaOficial {

    private static final Logger log = LoggerFactory.getLogger(SiigoFlatFileExporter.class);

    private final AccountingEntryGenerator accountingEntryGenerator = new AccountingEntryGenerator();

    public static final String DELIMITER = ",";

    @Override
    public byte[] exportBatch(ExportBatch batch, List<Invoice> invoices) {
        if (invoices == null || invoices.isEmpty()) {
            return new byte[0];
        }

        StringBuilder sb = new StringBuilder();

        // Header line
        sb.append(String.join(DELIMITER,
                "TIPO_COMPROBANTE",
                "CODIGO_COMPROBANTE",
                "NUMERO_DOCUMENTO",
                "CUENTA_CONTABLE",
                "DEBITO_O_CREDITO",
                "VALOR_SECUENCIA",
                "ANIO",
                "MES",
                "DIA",
                "CODIGO_VENDEDOR",
                "CODIGO_CIUDAD",
                "CODIGO_ZONA",
                "SECUENCIA",
                "CENTRO_COSTO",
                "SUBCENTRO_COSTO",
                "NIT",
                "SUCURSAL",
                "DESCRIPCION",
                "NUMERO_CHEQUE",
                "COMPROBANTE_ANULADO",
                "MOTIVO_DEVOLUCION",
                "FORMA_PAGO",
                "CARGO_1",
                "CARGO_2",
                "DESCUENTO_1",
                "DESCUENTO_2",
                "DESCUENTO_3",
                "FACTURA_A_AFECTAR"
        )).append("\r\n");

        for (Invoice invoice : invoices) {
            List<AccountingEntryLine> lines = accountingEntryGenerator.generateEntry(invoice, null);

            int compCode = invoice.getBillingType() == BillingType.F20 ? 20 : 1;
            String docNumber = invoice.getInvoiceNumber() != null && !invoice.getInvoiceNumber().isBlank()
                    ? invoice.getInvoiceNumber()
                    : (invoice.getBillingType() == BillingType.F20 ? "FEMG-" : "FESP-") + invoice.getId().toString().substring(0, 8).toUpperCase();

            LocalDate emissionDate = invoice.getIssueDate() != null ? invoice.getIssueDate() : LocalDate.now();
            String year = String.valueOf(emissionDate.getYear());
            String month = String.format("%02d", emissionDate.getMonthValue());
            String day = String.format("%02d", emissionDate.getDayOfMonth());

            String rawNit = invoice.getClientNit() != null ? invoice.getClientNit().replaceAll("-.*$", "").trim() : "";

            for (AccountingEntryLine entry : lines) {
                String debitOrCredit = entry.debit().isPositive() ? "D" : "C";
                BigDecimal value = entry.debit().isPositive() ? entry.debit().amount() : entry.credit().amount();

                // Clean description of quotes/commas for CSV safety
                String desc = entry.description() != null
                        ? entry.description().replace("\"", "'").replace(",", " ")
                        : "";

                List<String> row = List.of(
                        "F",                                        // Tipo comprobante
                        String.valueOf(compCode),                   // Código comprobante (1 o 20)
                        docNumber,                                  // Número documento
                        entry.accountCode(),                        // Cuenta contable
                        debitOrCredit,                              // Débito o Crédito
                        value.toPlainString(),                      // Valor
                        year,                                       // Año
                        month,                                      // Mes
                        day,                                        // Día
                        "13",                                       // Vendedor
                        "670",                                      // Ciudad (Pasto)
                        "4",                                        // Zona
                        String.valueOf(entry.sequence()),           // Secuencia
                        "10",                                       // Centro de costo
                        "1",                                        // Subcentro de costo
                        rawNit,                                     // NIT
                        "000",                                      // Sucursal
                        "\"" + desc + "\"",                         // Descripción
                        "0",                                        // Número de cheque
                        "N",                                        // Comprobante anulado
                        "0",                                        // Motivo devolución
                        "1",                                        // Forma de pago (Crédito)
                        "0.00",                                     // Cargo 1
                        "0.00",                                     // Cargo 2
                        "0.00",                                     // Descuento 1
                        "0.00",                                     // Descuento 2
                        "0.00",                                     // Descuento 3
                        ""                                          // Factura a debitar/acreditar
                );

                sb.append(String.join(DELIMITER, row)).append("\r\n");
            }
        }

        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
