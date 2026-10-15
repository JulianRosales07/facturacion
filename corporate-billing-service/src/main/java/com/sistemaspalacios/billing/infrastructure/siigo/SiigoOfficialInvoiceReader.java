package com.sistemaspalacios.billing.infrastructure.siigo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.service.dto.OfficialInvoiceData;
import com.sistemaspalacios.billing.service.siigo.OfficialInvoiceReader;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Adaptador del modelo general de movimiento contable incluido en Docs/archivosplanos. */
@Component
public class SiigoOfficialInvoiceReader implements OfficialInvoiceReader {
    private final ObjectMapper mapper;
    public SiigoOfficialInvoiceReader(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public List<OfficialInvoiceData> read(String filename, InputStream input) {
        return read(filename, input, null);
    }

    @Override
    public List<OfficialInvoiceData> read(String filename, InputStream input, java.time.Instant cutoffAt) {
        try {
            if (filename.toLowerCase(Locale.ROOT).endsWith(".json")) {
                return mapper.readValue(input, new TypeReference<List<OfficialInvoiceData>>() {});
            }
            try (Workbook workbook = WorkbookFactory.create(input)) {
                Sheet sheet = workbook.getSheetAt(0);

                // 1. Formato Movimiento Contable tradicional (Docs/archivosplanos)
                int movementHeader = -1;
                for (int i = 0; i <= Math.min(30, sheet.getLastRowNum()); i++) {
                    Row row = sheet.getRow(i);
                    if (text(row, 0).contains("TIPO DE COMPROBANTE") && text(row, 3).contains("CUENTA CONTABLE")
                            && text(row, 15).equals("NIT")) { movementHeader = i; break; }
                }
                if (movementHeader >= 0) {
                    return readMovementSpreadsheet(sheet, movementHeader);
                }

                // 2. Formato Saldos de Cartera real del servidor (Docs/server)
                int carteraHeader = -1;
                for (int i = 0; i <= Math.min(30, sheet.getLastRowNum()); i++) {
                    Row row = sheet.getRow(i);
                    String col0 = text(row, 0).toUpperCase(Locale.ROOT);
                    if (col0.contains("IDENTIFICACION") || col0.contains("IDENTIFICACIÓN")) {
                        carteraHeader = i;
                        break;
                    }
                }
                if (carteraHeader >= 0) {
                    validatePortfolioHeader(sheet.getRow(carteraHeader));
                    if (workbook.getNumberOfSheets() != 1) {
                        throw new IllegalArgumentException("Cartera con varias hojas requiere seleccionar y conciliar su alcance");
                    }
                    if (cutoffAt == null || cutoffAt.isAfter(java.time.Instant.now())) {
                        throw new IllegalArgumentException("Cartera requiere fecha y hora reales del corte, no futuras");
                    }
                    return readCarteraSpreadsheet(sheet, carteraHeader, cutoffAt);
                }

                return List.of();
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("No se pudo leer facturación oficial: " + ex.getMessage(), ex);
        }
    }

    private void validatePortfolioHeader(Row header) {
        Map<Integer, String> expected = Map.of(3, "TIPO DE COMPROBANTE", 4, "CODIGO COMPROBANTE",
                5, "NUMERO DE DOCUMENTO", 7, "FECHA VENCIMIENTO", 11, "SALDO", 16, "FECHA DOCUMENTO");
        expected.forEach((column, label) -> {
            String actual = java.text.Normalizer.normalize(text(header, column), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            if (!actual.startsWith(label)) {
                throw new IllegalArgumentException("Encabezado de cartera inválido en columna " + (column + 1) + ": se requiere " + label);
            }
        });
    }

    private List<OfficialInvoiceData> readMovementSpreadsheet(Sheet sheet, int header) {
        Map<String, Document> docs = new LinkedHashMap<>();
        for (int i = header + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (!text(row, 0).equals("F")) continue;
            String code = text(row, 1);
            if (!code.equals("1") && !code.equals("20")) continue;
            String number = text(row, 2), nit = text(row, 15);
            if (number.isBlank() || nit.isBlank()) throw new IllegalArgumentException("Documento o NIT vacío en fila " + (i + 1));
            LocalDate date = LocalDate.of(decimal(row, 6).intValueExact(), decimal(row, 7).intValueExact(), decimal(row, 8).intValueExact());
            String key = code + ":" + number;
            Document doc = docs.computeIfAbsent(key, k -> new Document(nit, code.equals("20") ? BillingType.F20 : BillingType.F1, number, date));
            if (!doc.date.equals(date)) throw new IllegalArgumentException("Datos contradictorios del documento " + key);
            if (text(row, 19).equalsIgnoreCase("S")) { doc.voided = true; continue; }
            String account = text(row, 3), nature = text(row, 4);
            Money value = Money.of(decimal(row, 5));
            if (account.startsWith("13") && nature.equals("D")) {
                if (doc.nit != null && !doc.nit.equals(nit)) throw new IllegalArgumentException("Varios deudores para " + key);
                doc.nit = nit;
                doc.total = doc.total.plus(value);
            }
            if (account.startsWith("4") && nature.equals("C")) {
                Money iva = Money.of(decimal(row, 68));
                BigDecimal quantity = decimal(row, 79);
                if (quantity.signum() <= 0) quantity = BigDecimal.ONE;
                doc.items.add(new OfficialInvoiceData.Item(text(row, 76) + "-" + text(row, 77) + "-" + text(row, 78),
                        text(row, 17), quantity, value, iva));
            }
        }
        return docs.values().stream().filter(d -> !d.voided).map(Document::data).toList();
    }

    private List<OfficialInvoiceData> readCarteraSpreadsheet(Sheet sheet, int header, java.time.Instant cutoffAt) {
        Map<String, OfficialInvoiceData> docs = new LinkedHashMap<>();
        Map<String, String> installments = new HashMap<>();
        for (int i = header + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            String nit = text(row, 0).replaceAll("[.,]", "").trim();
            String voucherType = text(row, 3).trim();
            if (!"F".equalsIgnoreCase(voucherType)) continue;

            String code = text(row, 4).trim();
            if (!"1".equals(code) && !"20".equals(code)) continue;

            String number = text(row, 5).trim().replaceFirst("^0+(?!$)", "");
            String currency = text(row, 8).trim();
            if (!currency.isBlank() && !"0".equals(currency) && !"COP".equalsIgnoreCase(currency)) {
                throw new IllegalArgumentException("Moneda distinta de COP requiere conciliación en fila " + (i + 1));
            }
            if (!nit.matches("[0-9]+") || !number.matches("[0-9]+")) {
                throw new IllegalArgumentException("NIT o consecutivo inválido en fila " + (i + 1));
            }

            if (text(row, 11).isBlank()) throw new IllegalArgumentException("Falta saldo en fila " + (i + 1));
            BigDecimal balanceValue = decimal(row, 11);
            Money balance = Money.of(balanceValue);

            LocalDate docDate = parseCarteraDate(cell(row, 16));
            if (docDate == null) throw new IllegalArgumentException("Fecha de documento inválida en fila " + (i + 1));
            if (docDate.isAfter(cutoffAt.atZone(java.time.ZoneId.of("America/Bogota")).toLocalDate())) {
                throw new IllegalArgumentException("Documento posterior al corte en fila " + (i + 1));
            }

            LocalDate dueDate = parseCarteraDate(cell(row, 7));
            if (dueDate == null && !text(row, 7).isBlank()) {
                throw new IllegalArgumentException("Fecha de vencimiento inválida en fila " + (i + 1));
            }

            BillingType billingType = "20".equals(code) ? BillingType.F20 : BillingType.F1;
            String key = billingType.name() + ":" + number;
            String installment = text(row, 6);
            String previousInstallment = installments.putIfAbsent(key, installment);
            if (previousInstallment != null && !previousInstallment.equals(installment)) {
                throw new IllegalArgumentException("Múltiples cuotas del documento requieren conciliación: " + key);
            }

            var items = List.of(new OfficialInvoiceData.Item(
                    "SERV-CORP",
                    "Facturación de servicios corporativos",
                    BigDecimal.ONE,
                    Money.ZERO,
                    Money.ZERO
            ));

            var data = new OfficialInvoiceData(nit, null, billingType, number, docDate, dueDate,
                    null, Money.ZERO, items, balance, cutoffAt);
            var previous = docs.putIfAbsent(key, data);
            if (previous != null && !previous.equals(data)) {
                throw new IllegalArgumentException("Filas contradictorias o cuotas sin conciliar para " + key + " en fila " + (i + 1));
            }
        }
        return new ArrayList<>(docs.values());
    }

    private Cell cell(Row row, int col) {
        return row != null ? row.getCell(col) : null;
    }

    private LocalDate parseCarteraDate(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
            long raw = Math.round(cell.getNumericCellValue());
            return parseCompactDate(raw);
        }
        String str = text(cell).replaceAll("[^0-9]", "");
        if (str.length() == 8) {
            try {
                return LocalDate.parse(str, java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(java.time.format.ResolverStyle.STRICT));
            } catch (Exception ignored) {}
        }
        return null;
    }

    private LocalDate parseCompactDate(long value) {
        if (value < 19000101 || value > 21001231) return null;
        try {
            return LocalDate.parse(String.valueOf(value), java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(java.time.format.ResolverStyle.STRICT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(Cell cell) {
        if (cell == null) return "";
        return cell.getCellType() == CellType.NUMERIC
                ? BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString()
                : new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
    }

    private String text(Row row, int col) {
        if (row == null || row.getCell(col) == null) return "";
        Cell cell = row.getCell(col);
        return cell.getCellType() == CellType.NUMERIC
                ? BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString()
                : new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
    }
    private BigDecimal decimal(Row row, int col) {
        String value = text(row, col);
        return value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }
    private static class Document {
        String nit;
        final String number;
        final BillingType type;
        final LocalDate date;
        Money total = Money.ZERO;
        boolean voided;
        List<OfficialInvoiceData.Item> items = new ArrayList<>();
        Document(String nit, BillingType type, String number, LocalDate date) {
            this.type = type; this.number = number; this.date = date;
        }
        OfficialInvoiceData data() { return new OfficialInvoiceData(nit, null, type, number, date, null, null, total, items); }
    }
}
