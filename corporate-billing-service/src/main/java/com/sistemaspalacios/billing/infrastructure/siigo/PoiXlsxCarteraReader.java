package com.sistemaspalacios.billing.infrastructure.siigo;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.service.siigo.SiigoCarteraReader;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Adaptador Apache POI del corte de cartera de Siigo (Sprint 13).
 * <p>
 * Decisiones de lectura que conviene conocer:
 * <ul>
 *   <li>Los encabezados están en la fila 5 y los datos arrancan en la 6. Se localiza la fila
 *       de encabezados buscando el texto {@code IDENTIFICACIÓN} en lugar de asumir un índice
 *       fijo, para tolerar que contabilidad agregue o quite filas de título.</li>
 *   <li>Las fechas vienen como <b>enteros yyyyMMdd</b> (20260925), no como fechas de Excel.
 *       Se soportan ambos formatos por si el archivo cambia.</li>
 *   <li>El saldo viene como <i>double</i> y arrastra ruido binario
 *       ({@code 1122566.6599999999}). Se lee con {@link BigDecimal} y se redondea
 *       {@code HALF_UP} a 2 decimales, como exige la norma del proyecto para importes.</li>
 *   <li>Un error en una fila no aborta la lectura: se reporta y se continúa.</li>
 * </ul>
 */
@Component
public class PoiXlsxCarteraReader implements SiigoCarteraReader {

    private static final Logger log = LoggerFactory.getLogger(PoiXlsxCarteraReader.class);

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String HEADER_ANCHOR = "IDENTIFICACION";

    // Índices de columna (0-based) según la estructura verificada del archivo real
    private static final int COL_NIT = 0;               // A
    private static final int COL_ACCOUNT = 2;           // C
    private static final int COL_VOUCHER_TYPE = 3;      // D
    private static final int COL_VOUCHER_CODE = 4;      // E
    private static final int COL_DOCUMENT_NUMBER = 5;   // F
    private static final int COL_DUE_DATE = 7;          // H
    private static final int COL_COST_CENTER = 9;       // J
    private static final int COL_BALANCE = 11;          // L
    private static final int COL_CITY = 14;             // O
    private static final int COL_ZONE = 15;             // P
    private static final int COL_DOCUMENT_DATE = 16;    // Q

    @Override
    public CarteraSnapshotData read(File spreadsheet) {
        List<CarteraRow> rows = new ArrayList<>();
        List<CarteraRowError> errors = new ArrayList<>();

        if (spreadsheet == null || !spreadsheet.exists() || !spreadsheet.isFile()) {
            return new CarteraSnapshotData(
                    spreadsheet != null ? spreadsheet.getName() : "?", null, List.of(), List.of());
        }

        try (Workbook workbook = WorkbookFactory.create(spreadsheet, null, true)) {
            Sheet sheet = workbook.getSheetAt(0);
            int headerRowIndex = locateHeaderRow(sheet);
            if (headerRowIndex < 0) {
                errors.add(new CarteraRowError(0,
                        "No se encontró la fila de encabezados (se busca la columna IDENTIFICACIÓN)"));
                return new CarteraSnapshotData(spreadsheet.getName(), null, rows, errors);
            }

            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlank(row)) {
                    continue;
                }
                int rowNumber = i + 1; // 1-based, igual que lo ve el usuario en Excel
                try {
                    CarteraRow parsed = parseRow(row, rowNumber);
                    if (parsed == null) {
                        continue;
                    }
                    rows.add(parsed);
                } catch (RuntimeException ex) {
                    errors.add(new CarteraRowError(rowNumber, truncate(ex.getMessage())));
                }
            }
        } catch (Exception ex) {
            log.error("No se pudo leer el corte de cartera {}: {}", spreadsheet.getName(), ex.getMessage(), ex);
            errors.add(new CarteraRowError(0, "Archivo ilegible: " + truncate(ex.getMessage())));
        }

        log.info("Cartera {}: {} filas válidas, {} con error; fecha de generación no informada",
                spreadsheet.getName(), rows.size(), errors.size());

        return new CarteraSnapshotData(spreadsheet.getName(), null, rows, errors);
    }

    /**
     * Busca la fila de encabezados por contenido en lugar de por índice fijo. Tolera tildes
     * y mayúsculas porque el archivo real trae {@code IDENTIFICACIÓN  (OBLIGATORIO)} con
     * doble espacio.
     */
    private int locateHeaderRow(Sheet sheet) {
        int limit = Math.min(sheet.getLastRowNum(), 30);
        for (int i = sheet.getFirstRowNum(); i <= limit; i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            String first = normalize(readString(row.getCell(COL_NIT)));
            if (first != null && first.contains(HEADER_ANCHOR)) {
                return i;
            }
        }
        return -1;
    }

    private CarteraRow parseRow(Row row, int rowNumber) {
        String nit = readString(row.getCell(COL_NIT));
        String documentNumber = readString(row.getCell(COL_DOCUMENT_NUMBER));

        // Fila de relleno o de totales: sin identificación ni documento no hay nada que aplicar.
        if (isEmpty(nit) && isEmpty(documentNumber)) {
            return null;
        }
        if (isEmpty(documentNumber)) {
            throw new IllegalArgumentException("Falta el número de documento (columna F)");
        }

        Integer voucherCode = readInteger(row.getCell(COL_VOUCHER_CODE));
        if (voucherCode == null) {
            throw new IllegalArgumentException("Falta el código de comprobante (columna E)");
        }

        Money balance = readMoney(row.getCell(COL_BALANCE));
        if (balance == null) {
            throw new IllegalArgumentException("Saldo no numérico (columna L)");
        }

        return new CarteraRow(
                rowNumber,
                normalizeNit(nit),
                readString(row.getCell(COL_ACCOUNT)),
                readString(row.getCell(COL_VOUCHER_TYPE)),
                voucherCode,
                documentNumber.trim(),
                toBillingType(voucherCode),
                balance,
                readCompactDate(row.getCell(COL_DUE_DATE)),
                readCompactDate(row.getCell(COL_DOCUMENT_DATE)),
                readString(row.getCell(COL_COST_CENTER)),
                readString(row.getCell(COL_CITY)),
                readString(row.getCell(COL_ZONE))
        );
    }

    /**
     * Código de comprobante 20 es F20 (FEMG); cualquier otro se trata como F1 (FESP),
     * igual que hace la clasificación de PDF por nombre de archivo.
     */
    private BillingType toBillingType(Integer voucherCode) {
        return (voucherCode != null && voucherCode == 20) ? BillingType.F20 : BillingType.F1;
    }

    // --- Lectura de celdas -----------------------------------------------------

    private String readString(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> new BigDecimal(String.valueOf(cell.getNumericCellValue()))
                    .stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> readFormulaAsString(cell);
            default -> null;
        };
    }

    private String readFormulaAsString(Cell cell) {
        try {
            return switch (cell.getCachedFormulaResultType()) {
                case STRING -> cell.getStringCellValue().trim();
                case NUMERIC -> new BigDecimal(String.valueOf(cell.getNumericCellValue()))
                        .stripTrailingZeros().toPlainString();
                default -> null;
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Integer readInteger(Cell cell) {
        String raw = readString(cell);
        if (isEmpty(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * El saldo se guarda como <i>double</i> en el xlsx y arrastra ruido binario. Se pasa por
     * {@code String} antes de {@link BigDecimal} para no amplificarlo, y se redondea HALF_UP.
     */
    private Money readMoney(Cell cell) {
        if (cell == null) {
            return null;
        }
        try {
            BigDecimal raw = switch (cell.getCellType()) {
                case NUMERIC -> new BigDecimal(String.valueOf(cell.getNumericCellValue()));
                case STRING -> new BigDecimal(cell.getStringCellValue().trim().replace(",", ""));
                case FORMULA -> new BigDecimal(String.valueOf(cell.getNumericCellValue()));
                default -> null;
            };
            if (raw == null) {
                return null;
            }
            return Money.of(raw.setScale(2, RoundingMode.HALF_UP));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Las fechas llegan como entero {@code yyyyMMdd}. Se soporta además la fecha nativa de
     * Excel por si el formato del archivo cambia.
     */
    private LocalDate readCompactDate(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String raw = readString(cell);
        if (isEmpty(raw)) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() != 8) {
            return null;
        }
        try {
            return LocalDate.parse(digits, COMPACT_DATE);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // --- Utilidades ------------------------------------------------------------

    private boolean isBlank(Row row) {
        for (int c = row.getFirstCellNum(); c >= 0 && c < row.getLastCellNum(); c++) {
            if (!isEmpty(readString(row.getCell(c)))) {
                return false;
            }
        }
        return true;
    }

    /** Quita el dígito de verificación y cualquier separador: {@code 900.163.149-2} -> {@code 900163149}. */
    private String normalizeNit(String nit) {
        if (nit == null) {
            return null;
        }
        String cleaned = nit.trim().replaceAll("[^0-9\\-]", "").replaceAll("-.*$", "");
        return cleaned.isBlank() ? null : cleaned;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private boolean isEmpty(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String message) {
        if (message == null) {
            return "Error de formato";
        }
        return message.length() > 500 ? message.substring(0, 497) + "..." : message;
    }
}
