package com.sistemaspalacios.billing.service;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

/** Arma en memoria el Excel de órdenes que recibe la pantalla de importación. */
final class NovedadesFacturaEnPreparacionTestSupport {

    static final String[] HEADERS = {"NIT", "CLIENTE", "ORDEN", "SEDE", "CANON", "CIUDAD", "FECHA",
            "NOVEDAD", "FECHA_EFECTIVA", "VALOR_AJUSTE", "OBSERVACION"};

    /** Una fila del archivo. {@code novedad} null = solo la sede, sin novedad. */
    record Fila(String nit, String orden, long canon, String novedad, LocalDate efectiva, String observacion) {
        static Fila sede(String nit, String orden, long canon) {
            return new Fila(nit, orden, canon, null, null, null);
        }

        static Fila novedad(String nit, String orden, String tipo, LocalDate efectiva, long nuevoValor) {
            return new Fila(nit, orden, nuevoValor, tipo, efectiva, tipo + " efectivo el " + efectiva);
        }
    }

    private NovedadesFacturaEnPreparacionTestSupport() {}

    static InputStream excel(List<Fila> filas) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CreationHelper helper = wb.getCreationHelper();
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));

            Sheet sheet = wb.createSheet("Ordenes");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) header.createCell(i).setCellValue(HEADERS[i]);

            int r = 1;
            for (Fila f : filas) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(f.nit());
                row.createCell(1).setCellValue("CLIENTE PRUEBA " + f.nit());
                row.createCell(2).setCellValue(f.orden());
                row.createCell(3).setCellValue("SEDE " + f.orden());
                row.createCell(4).setCellValue(f.canon());
                row.createCell(5).setCellValue("Pasto");
                var cert = row.createCell(6);
                cert.setCellValue(LocalDate.of(2026, 1, 1));
                cert.setCellStyle(dateStyle);
                if (f.novedad() != null) {
                    row.createCell(7).setCellValue(f.novedad());
                    var ef = row.createCell(8);
                    ef.setCellValue(f.efectiva());
                    ef.setCellStyle(dateStyle);
                    row.createCell(9).setCellValue(f.canon());
                    row.createCell(10).setCellValue(f.observacion());
                }
            }
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }
}
