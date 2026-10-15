package com.sistemaspalacios.billing.service.ordenes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import com.sistemaspalacios.billing.service.ordenes.OrderFileParser.ParsedFile;
import com.sistemaspalacios.billing.service.ordenes.OrderFileParser.ParsedRow;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncRecordDto;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderFileParserTest {

    private static final String[] HEADERS = {"NIT", "CLIENTE", "ORDEN", "SEDE", "CANON", "CIUDAD", "FECHA",
            "NOVEDAD", "FECHA_EFECTIVA", "VALOR_AJUSTE", "OBSERVACION"};

    private final OrderFileParser parser = new OrderFileParser(new ObjectMapper().registerModule(new JavaTimeModule()));

    /** Excel con las celdas como texto, tal como llegan cuando el archivo se arma a mano o se exporta de otro sistema. */
    private ParsedFile excel(String[] headers, Object[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            Sheet sheet = wb.createSheet("Ordenes");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object value = rows[r][c];
                    if (value instanceof String s) row.createCell(c).setCellValue(s);
                    else if (value instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                    else if (value instanceof LocalDate d) {
                        var cell = row.createCell(c);
                        cell.setCellValue(d);
                        cell.setCellStyle(dateStyle);
                    }
                }
            }
            wb.write(out);
            return parser.parse("ordenes.xlsx", new ByteArrayInputStream(out.toByteArray()), OrderOrigin.SPE);
        }
    }

    private ParsedFile csv(String content) throws Exception {
        return parser.parse("ordenes.csv", new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), OrderOrigin.SPE);
    }

    private static OrderSyncRecordDto onlyRecord(ParsedFile file) {
        assertNull(file.fileError(), file.fileError());
        assertEquals(1, file.rows().size());
        ParsedRow row = file.rows().get(0);
        assertTrue(row.isValid(), row.error());
        return row.record();
    }

    private static String onlyError(ParsedFile file) {
        assertNull(file.fileError(), file.fileError());
        assertEquals(1, file.rows().size());
        assertNotNull(file.rows().get(0).error(), "La fila debía rechazarse");
        return file.rows().get(0).error();
    }

    @Test
    @DisplayName("Fecha escrita como texto (como en novedad_cliente_900245045.xlsx) se respeta en lugar de usar la fecha de hoy")
    void textDatesAreParsed() throws Exception {
        OrderSyncRecordDto record = onlyRecord(excel(HEADERS,
                new Object[]{"900245045", "CLIENTE CORP SAS", "ORD-1", "SEDE NUEVA", 13161400, "Pasto", "2026-08-16",
                        "ALTA", "16/08/2026", 13161400, "Alta a mitad de periodo"}));

        assertEquals(LocalDate.of(2026, 8, 16), record.certificationDate());
        assertEquals(LocalDate.of(2026, 8, 16), record.updateEffectiveDate());
        assertEquals(OrderUpdateType.ALTA, record.updateType());
    }

    @Test
    @DisplayName("Fechas nativas de Excel, número de serie sin formato y entero AAAAMMDD")
    void numericDates() throws Exception {
        OrderSyncRecordDto record = onlyRecord(excel(HEADERS,
                new Object[]{"901555777", "PRUEBA", "PRB-1", "SEDE", 800000, "Pasto", LocalDate.of(2026, 1, 1),
                        "CAMBIO_PLAN", 46311, 800000, "Upgrade"}));
        assertEquals(LocalDate.of(2026, 1, 1), record.certificationDate());
        assertEquals(LocalDate.of(2026, 10, 16), record.updateEffectiveDate());

        OrderSyncRecordDto compact = onlyRecord(excel(HEADERS,
                new Object[]{"901555777", "PRUEBA", "PRB-1", "SEDE", 800000, "Pasto", 20261016}));
        assertEquals(LocalDate.of(2026, 10, 16), compact.certificationDate());
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "1500000|1500000",
            "1.500.000|1500000",
            "$ 1.500.000|1500000",
            "1.500.000,50|1500000.50",
            "1500000,50|1500000.50",
            "1,500,000.50|1500000.50",
            "2,000,000|2000000",
            "1.500|1500",
            "-150.000|-150000",
            "(150.000)|-150000"
    })
    @DisplayName("Valores en pesos escritos como texto")
    void amounts(String raw, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(OrderFileParser.parseAmount(raw)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"1.50.000", "15OO000", "1,5,0", "abc", "1.500.000.5"})
    @DisplayName("Un valor mal escrito se rechaza en lugar de leerse como cero o con otra magnitud")
    void invalidAmounts(String raw) {
        assertThrows(IllegalArgumentException.class, () -> OrderFileParser.parseAmount(raw));
    }

    @Test
    @DisplayName("CANON con texto inválido rechaza la fila y no factura cero")
    void invalidMonthlyValueRejectsRow() throws Exception {
        String error = onlyError(excel(HEADERS,
                new Object[]{"900245045", "CLIENTE", "ORD-1", "SEDE", "1.50.000", "Pasto", "2026-08-01"}));
        assertTrue(error.contains("CANON"), error);
    }

    @Test
    @DisplayName("CANON o FECHA vacíos rechazan la fila")
    void blankMonthlyValueOrDateRejectsRow() throws Exception {
        assertTrue(onlyError(excel(HEADERS,
                new Object[]{"900245045", "CLIENTE", "ORD-1", "SEDE", "", "Pasto", "2026-08-01"})).contains("CANON"));
        assertTrue(onlyError(excel(HEADERS,
                new Object[]{"900245045", "CLIENTE", "ORD-1", "SEDE", 100000, "Pasto", ""})).contains("FECHA"));
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "ALTA|ALTA",
            "Bajada de plan|CAMBIO_PLAN",
            "BAJADA_DE_PLAN|CAMBIO_PLAN",
            "Cambio de plan|CAMBIO_PLAN",
            "Upgrade|CAMBIO_PLAN",
            "Baja|BAJA",
            "Retiro|BAJA",
            "Suspensión|SUSPENSION",
            "Reactivación|REACTIVACION",
            "Traslado de dirección|TRASLADO_DIRECCION"
    })
    @DisplayName("Tipos de novedad: \"bajada de plan\" es un cambio de plan, no una baja de la sede")
    void updateTypes(String raw, OrderUpdateType expected) {
        assertEquals(expected, OrderFileParser.updateType(raw));
    }

    @Test
    @DisplayName("Novedad desconocida rechaza la fila en lugar de ignorarse")
    void unknownUpdateTypeRejectsRow() throws Exception {
        String error = onlyError(excel(HEADERS,
                new Object[]{"900245045", "CLIENTE", "ORD-1", "SEDE", 100000, "Pasto", "2026-08-01", "Cancelación del servicio parcial"}));
        assertTrue(error.contains("no reconocida"), error);
    }

    @Test
    @DisplayName("Sin columna CANON el archivo se rechaza: el valor mensual no se toma de VALOR_AJUSTE")
    void missingRequiredColumnRejectsFile() throws Exception {
        ParsedFile file = excel(new String[]{"NIT", "ORDEN", "FECHA", "NOVEDAD", "VALOR_AJUSTE"},
                new Object[]{"900245045", "ORD-1", "2026-08-01", "ALTA", 500000});
        assertNotNull(file.fileError());
        assertTrue(file.fileError().contains("CANON"), file.fileError());
    }

    @Test
    @DisplayName("Fila sin NIT se reporta con su número real de Excel")
    void blankNitIsReportedWithExcelRowNumber() throws Exception {
        ParsedFile file = excel(HEADERS,
                new Object[]{"900245045", "CLIENTE", "ORD-1", "SEDE", 100000, "Pasto", "2026-08-01"},
                new Object[]{"", "CLIENTE", "ORD-2", "SEDE", 100000, "Pasto", "2026-08-01"});
        assertEquals(2, file.rows().size());
        ParsedRow rejected = file.rows().get(1);
        assertEquals(3, rejected.rowNumber());
        assertTrue(rejected.error().contains("NIT"), rejected.error());
    }

    @Test
    @DisplayName("Datos opcionales vacíos llegan nulos para no sobrescribir al cliente (antes: 'Bogotá', 'Calle Principal')")
    void blankOptionalFieldsAreNull() throws Exception {
        OrderSyncRecordDto record = onlyRecord(excel(new String[]{"NIT", "ORDEN", "CANON", "FECHA"},
                new Object[]{"900.245.045-1", "ORD-1", 100000, "2026-08-01"}));
        assertEquals("900245045", record.nit());
        assertNull(record.businessName());
        assertNull(record.municipality());
        assertNull(record.address());
        assertNull(record.siteName());
        assertNull(record.updateType());
    }

    @Test
    @DisplayName("Texto más largo que la columna de base de datos rechaza la fila, no el archivo completo")
    void tooLongTextRejectsRow() throws Exception {
        String error = onlyError(excel(HEADERS,
                new Object[]{"900245045", "CLIENTE", "ORD-1", "S".repeat(101), 100000, "Pasto", "2026-08-01"}));
        assertTrue(error.contains("SEDE"), error);
    }

    @Test
    @DisplayName("CSV: comas dentro de comillas, fecha efectiva y valor de ajuste se leen igual que en Excel")
    void csvWithQuotedFields() throws Exception {
        OrderSyncRecordDto record = onlyRecord(csv("""
                NIT,CLIENTE,ORDEN,SEDE,DIRECCION,CANON,MUNICIPIO,FECHA,NOVEDAD,FECHA_EFECTIVA,VALOR_AJUSTE,OBSERVACION
                900245045,"CLIENTE CORP, SAS",ORD-900245045-NOV16,SEDE NUEVA,"Carrera 15 # 93-60, piso 2",13161400,Pasto,2026-08-01,CAMBIO_PLAN,2026-08-16,15161400,Upgrade
                """));
        assertEquals("CLIENTE CORP, SAS", record.businessName());
        assertEquals("Carrera 15 # 93-60, piso 2", record.address());
        assertEquals(0, new BigDecimal("13161400").compareTo(record.monthlyValue()));
        assertEquals(0, new BigDecimal("15161400").compareTo(record.updateValue()));
        assertEquals(LocalDate.of(2026, 8, 16), record.updateEffectiveDate());
        assertEquals(OrderUpdateType.CAMBIO_PLAN, record.updateType());
    }

    @Test
    @DisplayName("CSV separado por punto y coma con valores en formato colombiano")
    void semicolonCsv() throws Exception {
        OrderSyncRecordDto record = onlyRecord(csv("""
                NIT;ORDEN;CANON;FECHA
                900245045;ORD-1;1.500.000,00;01/08/2026
                """));
        assertEquals(0, new BigDecimal("1500000").compareTo(record.monthlyValue()));
        assertEquals(LocalDate.of(2026, 8, 1), record.certificationDate());
    }
}
