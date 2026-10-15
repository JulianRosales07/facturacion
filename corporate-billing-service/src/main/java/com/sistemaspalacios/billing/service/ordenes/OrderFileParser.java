package com.sistemaspalacios.billing.service.ordenes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncRecordDto;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte el archivo de órdenes (Excel, CSV/TSV o JSON por línea) en filas validadas.
 * <p>
 * Los valores que terminan en dinero o en fechas de cobro se leen de forma estricta: una celda
 * que no se puede interpretar rechaza la fila, con su número real en el archivo, en lugar de
 * reemplazarse por un valor por defecto (cero, la fecha de hoy, "Bogotá") que luego se
 * facturaría sin aviso. Los datos opcionales vacíos llegan como {@code null} para no
 * sobrescribir lo que ya tienen el cliente o la sede.
 * <p>
 * Los encabezados se reconocen por nombre exacto (sin tildes, mayúsculas ni espacios) contra
 * una lista de alias. Antes se buscaban por "contiene", y sin columna CANON el valor mensual
 * se leía de VALOR_AJUSTE o la columna NIC de MUNICIPIO.
 */
@Component
public class OrderFileParser {

    /** Una fila del archivo: el registro listo para procesar o el motivo de rechazo. */
    public record ParsedRow(int rowNumber, OrderSyncRecordDto record, String rawJson, String error) {
        public boolean isValid() {
            return error == null;
        }
    }

    /** Filas leídas; {@code fileError} indica que el archivo completo no se pudo interpretar. */
    public record ParsedFile(List<ParsedRow> rows, String fileError) {
        static ParsedFile rejected(String error) {
            return new ParsedFile(List.of(), error);
        }
    }

    private enum Column {
        NIT("NIT", true, 20, "nit", "identificacion", "documento", "nit_cliente", "numero_documento", "doc"),
        CLIENT("CLIENTE", false, 255, "cliente", "razon_social", "nombre_cliente", "empresa", "razon"),
        ORDER("ORDEN", true, 50, "orden", "codigo_orden", "orden_servicio", "codigo", "codigo_sede", "sede_cod", "external"),
        SITE("SEDE", false, 100, "sede", "nombre_sede", "sucursal", "sitename"),
        ADDRESS("DIRECCION", false, 255, "direccion", "address"),
        MONTHLY_VALUE("CANON", true, 0, "canon", "canon_mensual", "valor_mensual", "tarifa", "valor", "monto", "mensual"),
        NIC("NIC", false, 30, "nic"),
        EMAIL("EMAIL", false, 150, "email", "correo", "correo_electronico"),
        PHONE("TELEFONO", false, 50, "telefono", "celular"),
        MUNICIPALITY("MUNICIPIO", false, 60, "municipio", "ciudad"),
        CERTIFICATION_DATE("FECHA", true, 0, "fecha", "fecha_certificacion", "certificacion"),
        UPDATE_TYPE("NOVEDAD", false, 0, "novedad", "tipo_novedad", "evento", "tipo"),
        EFFECTIVE_DATE("FECHA_EFECTIVA", false, 0, "fecha_efectiva", "fecha_novedad"),
        UPDATE_VALUE("VALOR_AJUSTE", false, 0, "valor_ajuste", "ajuste", "diferencial", "valor_novedad"),
        OBSERVATION("OBSERVACION", false, 255, "observacion", "observaciones", "detalle", "comentario"),
        INSTALLATION_TOTAL("INSTALACION", false, 0, "instalacion", "cuota_total", "valor_instalacion"),
        INSTALLATION_COUNT("CUOTAS", false, 0, "cuotas", "total_cuotas", "numero_cuotas"),
        INSTALLATION_AMOUNT("VALOR_CUOTA", false, 0, "valor_cuota", "cuota_mensual");

        final String label;
        final boolean required;
        /** Longitud máxima de la columna en base de datos; 0 = no es texto libre. */
        final int maxLength;
        final List<String> aliases;

        Column(String label, boolean required, int maxLength, String... aliases) {
            this.label = label;
            this.required = required;
            this.maxLength = maxLength;
            this.aliases = List.of(aliases);
        }
    }

    /** Novedades aceptadas, ya normalizadas (mayúsculas, sin tildes, separadas por "_"). */
    private static final Map<String, OrderUpdateType> UPDATE_TYPES = Map.ofEntries(
            Map.entry("ALTA", OrderUpdateType.ALTA),
            Map.entry("ALTA_SEDE", OrderUpdateType.ALTA),
            Map.entry("ALTA_DE_SEDE", OrderUpdateType.ALTA),
            Map.entry("NUEVA_SEDE", OrderUpdateType.ALTA),
            Map.entry("BAJA", OrderUpdateType.BAJA),
            Map.entry("BAJA_SEDE", OrderUpdateType.BAJA),
            Map.entry("BAJA_DE_SEDE", OrderUpdateType.BAJA),
            Map.entry("RETIRO", OrderUpdateType.BAJA),
            Map.entry("CANCELACION", OrderUpdateType.BAJA),
            Map.entry("CAMBIO_PLAN", OrderUpdateType.CAMBIO_PLAN),
            Map.entry("CAMBIO_DE_PLAN", OrderUpdateType.CAMBIO_PLAN),
            Map.entry("UPGRADE", OrderUpdateType.CAMBIO_PLAN),
            Map.entry("DOWNGRADE", OrderUpdateType.CAMBIO_PLAN),
            Map.entry("SUBIDA_PLAN", OrderUpdateType.CAMBIO_PLAN),
            Map.entry("SUBIDA_DE_PLAN", OrderUpdateType.CAMBIO_PLAN),
            Map.entry("BAJADA_PLAN", OrderUpdateType.CAMBIO_PLAN),
            Map.entry("BAJADA_DE_PLAN", OrderUpdateType.CAMBIO_PLAN),
            Map.entry("CAMBIO_SERVICIO", OrderUpdateType.CAMBIO_SERVICIO),
            Map.entry("CAMBIO_DE_SERVICIO", OrderUpdateType.CAMBIO_SERVICIO),
            Map.entry("SUSPENSION", OrderUpdateType.SUSPENSION),
            Map.entry("SUSPENDIDO", OrderUpdateType.SUSPENSION),
            Map.entry("REACTIVACION", OrderUpdateType.REACTIVACION),
            Map.entry("REACTIVADO", OrderUpdateType.REACTIVACION),
            Map.entry("TRASLADO", OrderUpdateType.TRASLADO_DIRECCION),
            Map.entry("TRASLADO_DIRECCION", OrderUpdateType.TRASLADO_DIRECCION),
            Map.entry("TRASLADO_DE_DIRECCION", OrderUpdateType.TRASLADO_DIRECCION),
            Map.entry("CAMBIO_DIRECCION", OrderUpdateType.TRASLADO_DIRECCION),
            Map.entry("CAMBIO_DE_DIRECCION", OrderUpdateType.TRASLADO_DIRECCION)
    );

    /** Valores de la columna NOVEDAD que significan "esta fila no trae novedad". */
    private static final List<String> NO_UPDATE = List.of("NA", "N_A", "NINGUNA", "SIN_NOVEDAD", "NO");

    private static final List<DateTimeFormatter> TEXT_DATE_FORMATS = List.of(
            strict("uuuu-M-d"), strict("uuuu/M/d"), strict("d/M/uuuu"), strict("d-M-uuuu"), strict("uuuuMMdd"));

    private static final Pattern PLAIN_AMOUNT = Pattern.compile("\\d+");
    private static final Pattern DOT_GROUPED = Pattern.compile("\\d{1,3}(\\.\\d{3})+(,\\d{1,2})?|\\d+,\\d{1,2}");
    private static final Pattern COMMA_GROUPED = Pattern.compile("\\d{1,3}(,\\d{3})+(\\.\\d{1,2})?|\\d+\\.\\d{1,2}");
    private static final Pattern NIT_DIGITS = Pattern.compile("\\d{5,20}");
    private static final Pattern INSTALLMENTS = Pattern.compile("(\\d{1,3})(\\s*cuotas?)?", Pattern.CASE_INSENSITIVE);

    private static final int HEADER_SEARCH_ROWS = 10;

    private final ObjectMapper objectMapper;

    public OrderFileParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedFile parse(String fileName, InputStream input, OrderOrigin origin) throws IOException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseWorkbook(input, origin);
        }
        List<String> lines = decode(input.readAllBytes()).lines().toList();
        int first = firstNonBlank(lines);
        if (first < 0) {
            return ParsedFile.rejected("El archivo está vacío");
        }
        if (lines.get(first).trim().startsWith("{")) {
            return parseJsonLines(lines);
        }
        return parseDelimited(lines, first, origin);
    }

    // --- Formatos ---------------------------------------------------------------

    private ParsedFile parseWorkbook(InputStream input, OrderOrigin origin) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            int headerIndex = -1;
            Map<Column, Integer> columns = null;
            for (int r = sheet.getFirstRowNum(); r <= Math.min(HEADER_SEARCH_ROWS, sheet.getLastRowNum()); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                List<String> headers = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    headers.add(new ExcelRow(row).text(c));
                }
                if (containsNitHeader(headers)) {
                    headerIndex = r;
                    columns = mapColumns(headers);
                    break;
                }
            }
            if (headerIndex < 0) {
                return ParsedFile.rejected("No se encontró la fila de encabezados: se espera una columna NIT en las primeras "
                        + (HEADER_SEARCH_ROWS + 1) + " filas de la primera hoja");
            }
            String missing = missingRequired(columns);
            if (missing != null) {
                return ParsedFile.rejected(missing);
            }

            List<ParsedRow> rows = new ArrayList<>();
            for (int r = headerIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                ExcelRow reader = new ExcelRow(row);
                if (!reader.isBlank()) {
                    rows.add(toParsedRow(r + 1, reader, columns, origin));
                }
            }
            return new ParsedFile(rows, null);
        }
    }

    private ParsedFile parseDelimited(List<String> lines, int headerIndex, OrderOrigin origin) {
        String headerLine = lines.get(headerIndex);
        char delimiter = detectDelimiter(headerLine);
        List<String> headers = splitDelimited(headerLine, delimiter);
        if (!containsNitHeader(headers)) {
            return ParsedFile.rejected("La primera línea debe ser de encabezados e incluir la columna NIT");
        }
        Map<Column, Integer> columns = mapColumns(headers);
        String missing = missingRequired(columns);
        if (missing != null) {
            return ParsedFile.rejected(missing);
        }

        List<ParsedRow> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            TextRow reader = new TextRow(splitDelimited(lines.get(i), delimiter));
            if (!reader.isBlank()) {
                rows.add(toParsedRow(i + 1, reader, columns, origin));
            }
        }
        return new ParsedFile(rows, null);
    }

    private ParsedFile parseJsonLines(List<String> lines) {
        List<ParsedRow> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            int rowNumber = i + 1;
            try {
                OrderSyncRecordDto record = objectMapper.readValue(line, OrderSyncRecordDto.class);
                if (isBlank(record.nit()) || isBlank(record.externalOrderCode())) {
                    rows.add(new ParsedRow(rowNumber, null, line, "Los campos nit y externalOrderCode son obligatorios"));
                } else {
                    rows.add(new ParsedRow(rowNumber, record, line, null));
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                rows.add(new ParsedRow(rowNumber, null, toJson(Map.of("linea", line)), "JSON inválido: " + e.getOriginalMessage()));
            }
        }
        return new ParsedFile(rows, null);
    }

    // --- Fila -> registro -------------------------------------------------------

    private ParsedRow toParsedRow(int rowNumber, RowReader row, Map<Column, Integer> columns, OrderOrigin origin) {
        try {
            OrderSyncRecordDto record = toRecord(row, columns, origin);
            return new ParsedRow(rowNumber, record, toJson(record), null);
        } catch (IllegalArgumentException e) {
            Map<String, String> raw = new LinkedHashMap<>();
            columns.forEach((column, index) -> raw.put(column.label, row.text(index)));
            return new ParsedRow(rowNumber, null, toJson(raw), e.getMessage());
        }
    }

    private OrderSyncRecordDto toRecord(RowReader row, Map<Column, Integer> columns, OrderOrigin origin) {
        String nit = normalizeNit(text(row, columns, Column.NIT));
        String orderCode = text(row, columns, Column.ORDER);
        if (orderCode == null) {
            throw new IllegalArgumentException("La columna ORDEN está vacía: sin código de orden la sede no se puede identificar");
        }

        BigDecimal monthlyValue = amount(row, columns, Column.MONTHLY_VALUE);
        if (monthlyValue == null) {
            throw new IllegalArgumentException("La columna CANON está vacía: escriba el valor mensual de la sede (0 si no se cobra)");
        }
        if (monthlyValue.signum() < 0) {
            throw new IllegalArgumentException("El CANON no puede ser negativo: " + monthlyValue.toPlainString());
        }

        LocalDate certificationDate = date(row, columns, Column.CERTIFICATION_DATE);
        if (certificationDate == null) {
            throw new IllegalArgumentException("La columna FECHA está vacía: es la fecha desde la que se cobra la sede");
        }

        OrderUpdateType updateType = updateType(text(row, columns, Column.UPDATE_TYPE));
        LocalDate effectiveDate = date(row, columns, Column.EFFECTIVE_DATE);
        BigDecimal updateValue = amount(row, columns, Column.UPDATE_VALUE);

        BigDecimal installationTotal = amount(row, columns, Column.INSTALLATION_TOTAL);
        Integer installationCount = installments(text(row, columns, Column.INSTALLATION_COUNT));
        BigDecimal installationAmount = amount(row, columns, Column.INSTALLATION_AMOUNT);
        if (installationTotal != null && installationCount != null && installationAmount == null) {
            installationAmount = installationTotal.divide(BigDecimal.valueOf(installationCount), 2, RoundingMode.HALF_UP);
        }

        return new OrderSyncRecordDto(
                origin,
                nit,
                text(row, columns, Column.CLIENT),
                orderCode,
                text(row, columns, Column.NIC),
                text(row, columns, Column.SITE),
                text(row, columns, Column.ADDRESS),
                monthlyValue,
                certificationDate,
                null,
                null,
                null,
                text(row, columns, Column.MUNICIPALITY),
                null,
                text(row, columns, Column.EMAIL),
                text(row, columns, Column.PHONE),
                installationTotal,
                installationCount,
                installationAmount,
                updateType,
                updateValue != null ? updateValue : monthlyValue,
                effectiveDate != null ? effectiveDate : certificationDate,
                text(row, columns, Column.OBSERVATION)
        );
    }

    /** Texto de la columna o {@code null} si está vacía o el archivo no la trae. */
    private static String text(RowReader row, Map<Column, Integer> columns, Column column) {
        Integer index = columns.get(column);
        String value = index == null ? null : row.text(index);
        if (value != null && column.maxLength > 0 && value.length() > column.maxLength) {
            throw new IllegalArgumentException(String.format("La columna %s supera %d caracteres (%d)",
                    column.label, column.maxLength, value.length()));
        }
        return value;
    }

    private static BigDecimal amount(RowReader row, Map<Column, Integer> columns, Column column) {
        Integer index = columns.get(column);
        if (index == null) return null;
        try {
            return row.amount(index);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Columna " + column.label + ": " + e.getMessage());
        }
    }

    private static LocalDate date(RowReader row, Map<Column, Integer> columns, Column column) {
        Integer index = columns.get(column);
        if (index == null) return null;
        try {
            return row.date(index);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Columna " + column.label + ": " + e.getMessage());
        }
    }

    // --- Interpretación de valores ----------------------------------------------

    /** Quita puntos, espacios y dígito de verificación: {@code 900.245.045-1} → {@code 900245045}. */
    static String normalizeNit(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("La columna NIT está vacía");
        }
        String clean = raw.replaceAll("[.,\\s]", "");
        int dash = clean.indexOf('-');
        if (dash > 0) clean = clean.substring(0, dash);
        if (!NIT_DIGITS.matcher(clean).matches()) {
            throw new IllegalArgumentException("El NIT '" + raw + "' no es válido: debe tener solo dígitos");
        }
        return clean;
    }

    /**
     * Interpreta un valor en pesos escrito como texto. Acepta "1500000", "1.500.000",
     * "1.500.000,50", "1,500,000.50", "$ 1.500.000" y negativos con "-" o entre paréntesis.
     * Un separador seguido de exactamente tres dígitos se toma como separador de miles (un
     * valor en pesos nunca tiene tres decimales); cualquier otra forma se rechaza. Antes se
     * borraban las comas, y "1500000,50" se leía como 150.000.050.
     */
    static BigDecimal parseAmount(String raw) {
        String s = raw.trim().replace("$", "").replaceAll("(?i)cop", "").replaceAll("\\s", "");
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1);
        }
        if (s.startsWith("-")) {
            negative = !negative;
            s = s.substring(1);
        }
        String normalized;
        if (PLAIN_AMOUNT.matcher(s).matches()) {
            normalized = s;
        } else if (DOT_GROUPED.matcher(s).matches()) {
            normalized = s.replace(".", "").replace(',', '.');
        } else if (COMMA_GROUPED.matcher(s).matches()) {
            normalized = s.replace(",", "");
        } else {
            throw new IllegalArgumentException("'" + raw + "' no es un valor en pesos válido");
        }
        BigDecimal value = new BigDecimal(normalized);
        return negative ? value.negate() : value;
    }

    static LocalDate parseDate(String raw) {
        String s = raw.trim();
        int timeSeparator = s.indexOf(' ') > 0 ? s.indexOf(' ') : s.indexOf('T');
        if (timeSeparator > 0) s = s.substring(0, timeSeparator);
        for (DateTimeFormatter format : TEXT_DATE_FORMATS) {
            try {
                return checkedYear(LocalDate.parse(s, format), raw);
            } catch (DateTimeParseException ignored) {
                // se prueba el siguiente formato
            }
        }
        throw new IllegalArgumentException("'" + raw + "' no es una fecha válida (use AAAA-MM-DD o DD/MM/AAAA)");
    }

    /** Número sin formato de fecha: entero AAAAMMDD (20260816) o número de serie de Excel. */
    static LocalDate dateFromNumber(double value) {
        if (value != Math.rint(value)) {
            throw new IllegalArgumentException("'" + value + "' no es una fecha válida");
        }
        long number = (long) value;
        if (number >= 19000101L && number <= 21001231L) {
            return parseDate(Long.toString(number));
        }
        if (number >= 20000 && number <= 80000) {
            return checkedYear(DateUtil.getLocalDateTime(value).toLocalDate(), Long.toString(number));
        }
        throw new IllegalArgumentException("'" + number + "' no es una fecha válida");
    }

    private static LocalDate checkedYear(LocalDate date, String raw) {
        if (date.getYear() < 2000 || date.getYear() > 2100) {
            throw new IllegalArgumentException("'" + raw + "' tiene un año fuera de rango");
        }
        return date;
    }

    static OrderUpdateType updateType(String raw) {
        if (raw == null) return null;
        String key = normalizeKey(raw).toUpperCase(Locale.ROOT);
        if (key.isEmpty() || NO_UPDATE.contains(key)) return null;
        OrderUpdateType type = UPDATE_TYPES.get(key);
        if (type == null) {
            throw new IllegalArgumentException("Novedad '" + raw + "' no reconocida. Use ALTA, BAJA, CAMBIO_PLAN, "
                    + "CAMBIO_SERVICIO, SUSPENSION, REACTIVACION o TRASLADO_DIRECCION");
        }
        return type;
    }

    private static Integer installments(String raw) {
        if (raw == null) return null;
        Matcher matcher = INSTALLMENTS.matcher(raw.trim());
        int count = matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
        if (count <= 0) {
            throw new IllegalArgumentException("Columna CUOTAS: '" + raw + "' no es un número de cuotas válido");
        }
        return count;
    }

    // --- Encabezados ------------------------------------------------------------

    /** "Fecha Efectiva", "FECHA_EFECTIVA" y "fecha-efectiva" → {@code fecha_efectiva}. */
    static String normalizeKey(String value) {
        if (value == null) return "";
        String withoutAccents = Normalizer.normalize(value.replace("﻿", ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static boolean containsNitHeader(List<String> headers) {
        return headers.stream().map(OrderFileParser::normalizeKey).anyMatch(Column.NIT.aliases::contains);
    }

    /** Cada campo toma la primera de sus columnas alias presente en el archivo. */
    private static Map<Column, Integer> mapColumns(List<String> headers) {
        List<String> keys = headers.stream().map(OrderFileParser::normalizeKey).toList();
        Map<Column, Integer> columns = new EnumMap<>(Column.class);
        for (Column column : Column.values()) {
            for (String alias : column.aliases) {
                int index = keys.indexOf(alias);
                if (index >= 0 && !columns.containsValue(index)) {
                    columns.put(column, index);
                    break;
                }
            }
        }
        return columns;
    }

    private static String missingRequired(Map<Column, Integer> columns) {
        List<String> missing = new ArrayList<>();
        for (Column column : Column.values()) {
            if (column.required && !columns.containsKey(column)) {
                missing.add(column.label);
            }
        }
        return missing.isEmpty() ? null : "Faltan columnas obligatorias en el encabezado: " + String.join(", ", missing);
    }

    // --- Archivos planos --------------------------------------------------------

    /** UTF-8 si el contenido lo es; si no, Windows-1252 (CSV guardado desde Excel en Windows). */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    private static int firstNonBlank(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) return i;
        }
        return -1;
    }

    private static char detectDelimiter(String header) {
        long semicolons = header.chars().filter(c -> c == ';').count();
        long tabs = header.chars().filter(c -> c == '\t').count();
        long commas = header.chars().filter(c -> c == ',').count();
        if (tabs > semicolons && tabs > commas) return '\t';
        return semicolons > commas ? ';' : ',';
    }

    /** Separa una línea respetando comillas: {@code "CORP, SAS"} es un solo campo. */
    static List<String> splitDelimited(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    // --- Lectura de celdas ------------------------------------------------------

    /** Acceso a una fila sin importar el formato: el Excel conserva el tipo de celda, el plano solo texto. */
    private interface RowReader {
        String text(int column);

        BigDecimal amount(int column);

        LocalDate date(int column);

        boolean isBlank();
    }

    private static final class TextRow implements RowReader {
        private final List<String> fields;

        TextRow(List<String> fields) {
            this.fields = fields;
        }

        @Override
        public String text(int column) {
            if (column >= fields.size()) return null;
            String value = fields.get(column).trim();
            return value.isEmpty() ? null : value;
        }

        @Override
        public BigDecimal amount(int column) {
            String value = text(column);
            return value == null ? null : parseAmount(value);
        }

        @Override
        public LocalDate date(int column) {
            String value = text(column);
            return value == null ? null : parseDate(value);
        }

        @Override
        public boolean isBlank() {
            return fields.stream().allMatch(String::isBlank);
        }
    }

    private static final class ExcelRow implements RowReader {
        private static final DataFormatter FORMATTER = new DataFormatter(Locale.ROOT);
        private final Row row;

        ExcelRow(Row row) {
            this.row = row;
        }

        /** Tipo efectivo: el de la celda o, si es fórmula, el de su último resultado calculado. */
        private static CellType typeOf(Cell cell) {
            return cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        }

        @Override
        public String text(int column) {
            Cell cell = row.getCell(column);
            if (cell == null) return null;
            String value = switch (typeOf(cell)) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                        ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                        // 900245045 y no "9.00245045E8"; 12345 y no "12345.0"
                        : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case BLANK, ERROR, _NONE -> null;
                default -> FORMATTER.formatCellValue(cell);
            };
            if (value == null) return null;
            value = value.trim();
            return value.isEmpty() ? null : value;
        }

        @Override
        public BigDecimal amount(int column) {
            Cell cell = row.getCell(column);
            if (cell == null) return null;
            if (typeOf(cell) == CellType.NUMERIC) {
                // El double de Excel arrastra ruido binario (1122566.6599999999): se fija al centavo.
                return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
            }
            String value = text(column);
            return value == null ? null : parseAmount(value);
        }

        @Override
        public LocalDate date(int column) {
            Cell cell = row.getCell(column);
            if (cell == null) return null;
            if (typeOf(cell) == CellType.NUMERIC) {
                return DateUtil.isCellDateFormatted(cell)
                        ? checkedYear(cell.getLocalDateTimeCellValue().toLocalDate(), String.valueOf(cell.getNumericCellValue()))
                        : dateFromNumber(cell.getNumericCellValue());
            }
            String value = text(column);
            return value == null ? null : parseDate(value);
        }

        @Override
        public boolean isBlank() {
            for (int c = Math.max(0, row.getFirstCellNum()); c < row.getLastCellNum(); c++) {
                if (text(c) != null) return false;
            }
            return true;
        }
    }

    // --- Utilidades -------------------------------------------------------------

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            return "{}";
        }
    }
}
