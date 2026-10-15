package com.sistemaspalacios.billing.infrastructure.siigo;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.service.siigo.SiigoCarteraReader;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 13: lectura del corte de cartera de Siigo.
 * <p>
 * El libro de prueba replica la estructura real verificada sobre
 * {@code saldos_de_cartera.xlsx}: título en la fila 1, encabezados en la fila 5, datos desde
 * la fila 6, fechas como enteros {@code yyyyMMdd} y saldos como <i>double</i>.
 */
class PoiXlsxCarteraReaderTest {

    @TempDir
    Path tempDir;

    private PoiXlsxCarteraReader reader;

    @BeforeEach
    void setUp() {
        reader = new PoiXlsxCarteraReader();
    }

    /**
     * Varargs en lugar de {@code List<Object[]>}: pasar un solo {@code Object[]} a
     * {@code List.of(...)} lo interpreta como varargs de sus elementos y la inferencia falla.
     */
    private File buildWorkbook(String name, Object[]... dataRows) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook wb = new XSSFWorkbook(); FileOutputStream out = new FileOutputStream(file)) {
            Sheet sheet = wb.createSheet("Hoja1");

            sheet.createRow(0).createCell(0).setCellValue("SP SISTEMAS PALACIOS LTDA");
            sheet.createRow(1).createCell(0).setCellValue("MODELO SALDOS DE CARTERA");
            sheet.createRow(2);
            sheet.createRow(3);

            Row header = sheet.createRow(4);
            String[] headers = {
                    "IDENTIFICACIÓN  (OBLIGATORIO)", "SUCURSAL  (OBLIGATORIO)", "CUENTA (OBLIGATORIO)",
                    "TIPO DE COMPROBANTE (OBLIGATORIO)", "CÓDIGO COMPROBANTE  (OBLIGATORIO)",
                    "NÚMERO DE DOCUMENTO (OBLIGATORIO)", "VENCIMIENTO (OBLIGATORIO)", "FECHA VENCIMIENTO",
                    "MONEDA", "CENTRO DE COSTO", "SUB CENTRO DE COSTO", "SALDO", "EXTRANJERA",
                    "VENDEDOR", "CIUDAD", "ZONA", "FECHA DOCUMENTO"
            };
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }

            int rowIdx = 5;
            for (Object[] data : dataRows) {
                Row row = sheet.createRow(rowIdx++);
                for (int c = 0; c < data.length; c++) {
                    Object v = data[c];
                    if (v == null) continue;
                    if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else {
                        row.createCell(c).setCellValue(String.valueOf(v));
                    }
                }
            }
            wb.write(out);
        }
        return file;
    }

    /** Fila con la forma exacta del archivo real (la 1023 del ejemplo: consecutivo 51993). */
    private Object[] realRow() {
        return new Object[]{
                1088970617L, 0, 1305051001L, "F", 1, 51993L, 1, 20260925L, 0, 10, 1,
                5694150.0, 0, 10, 467, 1, 20260910L
        };
    }

    @Test
    @DisplayName("Lee la fila real del corte: NIT, tipo de facturación, consecutivo, saldo y fechas")
    void testReadsRealRowShape() throws Exception {
        File file = buildWorkbook("saldos_de_cartera.xlsx", realRow());

        SiigoCarteraReader.CarteraSnapshotData data = reader.read(file);

        assertTrue(data.errors().isEmpty(), "No debe haber errores de formato: " + data.errors());
        assertEquals(1, data.rows().size());

        SiigoCarteraReader.CarteraRow row = data.rows().get(0);
        assertEquals(6, row.rowNumber(), "La primera fila de datos es la 6 en Excel");
        assertEquals("1088970617", row.clientNit());
        assertEquals("1305051001", row.accountingAccount());
        assertEquals("F", row.voucherType());
        assertEquals(1, row.voucherCode());
        assertEquals("51993", row.documentNumber());
        assertEquals(BillingType.F1, row.billingType());
        assertEquals(Money.of(5_694_150), row.balance());
        assertEquals(LocalDate.of(2026, 9, 25), row.dueDate());
        assertEquals(LocalDate.of(2026, 9, 10), row.documentDate());
    }

    @Test
    @DisplayName("El código de comprobante 20 se mapea a F20 y cualquier otro a F1")
    void testVoucherCodeMapsToBillingType() throws Exception {
        File file = buildWorkbook("cartera.xlsx",
                new Object[]{900123456L, 0, 1305051007L, "F", 20, 278L, 1, 20260916L, 0, 20, 1, 60000.0, 0, 20, 546, 20, 20260901L},
                new Object[]{900654321L, 0, 1305051001L, "F", 1, 51759L, 1, 20260916L, 0, 10, 1, 856800.0, 0, 10, 128, 1, 20260901L});

        SiigoCarteraReader.CarteraSnapshotData data = reader.read(file);

        assertEquals(2, data.rows().size());
        assertEquals(BillingType.F20, data.rows().get(0).billingType());
        assertEquals(BillingType.F1, data.rows().get(1).billingType());
    }

    @Test
    @DisplayName("El ruido binario del double se redondea HALF_UP a 2 decimales")
    void testFloatingPointNoiseIsRounded() throws Exception {
        // Valor tomado del archivo real: 1122566.6599999999
        File file = buildWorkbook("cartera.xlsx",
                new Object[]{12748943L, 0, 1305051001L, "F", 1, 51942L, 1, 20260916L, 0, 10, 1,
                        1122566.6599999999, 0, 10, 58, 1, 20260902L});

        SiigoCarteraReader.CarteraSnapshotData data = reader.read(file);

        assertEquals(1, data.rows().size());
        assertEquals(Money.of(new java.math.BigDecimal("1122566.66")), data.rows().get(0).balance());
        assertEquals(2, data.rows().get(0).balance().amount().scale());
    }

    @Test
    @DisplayName("La fecha de corte es la fecha de documento más reciente del archivo")
    void testCutoffDateIsMostRecentDocumentDate() throws Exception {
        File file = buildWorkbook("cartera.xlsx",
                new Object[]{111L, 0, 1305051001L, "F", 1, 40623L, 1, 20251216L, 0, 10, 1, 59500.0, 0, 10, 670, 1, 20251201L},
                new Object[]{222L, 0, 1305051001L, "F", 1, 51993L, 1, 20260925L, 0, 10, 1, 59500.0, 0, 10, 670, 1, 20260910L},
                new Object[]{333L, 0, 1305051001L, "F", 1, 51942L, 1, 20260916L, 0, 10, 1, 59500.0, 0, 10, 670, 1, 20260902L});

        SiigoCarteraReader.CarteraSnapshotData data = reader.read(file);

        assertNull(data.cutoffDate(), "La fecha de una factura no acredita la generación del corte");
    }

    @Test
    @DisplayName("Una fila con error de formato no aborta la lectura del resto")
    void testFormatErrorDoesNotAbortRead() throws Exception {
        File file = buildWorkbook("cartera.xlsx",
                new Object[]{111L, 0, 1305051001L, "F", 1, 51759L, 1, 20260916L, 0, 10, 1, 856800.0, 0, 10, 128, 1, 20260901L},
                // Sin código de comprobante (columna E) y sin saldo
                new Object[]{222L, 0, 1305051001L, "F", null, 51760L, 1, 20260916L, 0, 10, 1, null, 0, 10, 128, 1, 20260901L},
                new Object[]{333L, 0, 1305051001L, "F", 1, 51761L, 1, 20260916L, 0, 10, 1, 59500.0, 0, 10, 128, 1, 20260901L});

        SiigoCarteraReader.CarteraSnapshotData data = reader.read(file);

        assertEquals(2, data.rows().size(), "Las dos filas válidas deben leerse");
        assertEquals(1, data.errors().size(), "La fila inválida debe reportarse, no perderse");
        assertEquals(7, data.errors().get(0).rowNumber());
        assertEquals(3, data.totalRows());
    }

    @Test
    @DisplayName("Los encabezados se localizan por contenido, no por índice fijo")
    void testHeaderIsLocatedByContent() throws Exception {
        File file = tempDir.resolve("desplazado.xlsx").toFile();
        try (XSSFWorkbook wb = new XSSFWorkbook(); FileOutputStream out = new FileOutputStream(file)) {
            Sheet sheet = wb.createSheet("Hoja1");
            // Tres filas de título extra: los encabezados quedan en la 8, no en la 5
            for (int i = 0; i < 7; i++) {
                sheet.createRow(i).createCell(0).setCellValue("RELLENO " + i);
            }
            Row header = sheet.createRow(7);
            header.createCell(0).setCellValue("IDENTIFICACIÓN  (OBLIGATORIO)");
            header.createCell(4).setCellValue("CÓDIGO COMPROBANTE  (OBLIGATORIO)");
            header.createCell(5).setCellValue("NÚMERO DE DOCUMENTO (OBLIGATORIO)");
            header.createCell(11).setCellValue("SALDO");

            Row data = sheet.createRow(8);
            data.createCell(0).setCellValue(1088970617D);
            data.createCell(4).setCellValue(1D);
            data.createCell(5).setCellValue(51993D);
            data.createCell(11).setCellValue(5694150D);
            wb.write(out);
        }

        SiigoCarteraReader.CarteraSnapshotData result = reader.read(file);

        assertEquals(1, result.rows().size());
        assertEquals("51993", result.rows().get(0).documentNumber());
    }

    @Test
    @DisplayName("Un archivo inexistente devuelve resultado vacío sin lanzar excepción")
    void testMissingFileIsHandledGracefully() {
        SiigoCarteraReader.CarteraSnapshotData data = reader.read(new File(tempDir.toFile(), "no_existe.xlsx"));

        assertNotNull(data);
        assertTrue(data.rows().isEmpty());
        assertNull(data.cutoffDate());
    }

    @Test
    @DisplayName("El NIT se normaliza quitando el dígito de verificación y los separadores")
    void testNitIsNormalized() throws Exception {
        File file = buildWorkbook("cartera.xlsx",
                new Object[]{"900.163.149-2", 0, 1305051001L, "F", 1, 51759L, 1, 20260916L, 0, 10, 1, 856800.0, 0, 10, 128, 1, 20260901L});

        SiigoCarteraReader.CarteraSnapshotData data = reader.read(file);

        assertEquals(1, data.rows().size());
        assertEquals("900163149", data.rows().get(0).clientNit());
    }

    @Test
    @DisplayName("Las filas totalmente vacías se ignoran sin contarse como error")
    void testBlankRowsAreSkipped() throws Exception {
        File file = buildWorkbook("cartera.xlsx",
                new Object[]{111L, 0, 1305051001L, "F", 1, 51759L, 1, 20260916L, 0, 10, 1, 856800.0, 0, 10, 128, 1, 20260901L},
                new Object[]{},
                new Object[]{});

        SiigoCarteraReader.CarteraSnapshotData data = reader.read(file);

        assertEquals(1, data.rows().size());
        assertTrue(data.errors().isEmpty());
        assertFalse(data.rows().isEmpty());
    }
}
