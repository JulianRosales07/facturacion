package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.service.WithholdingAuditQueryService.AuditEventRow;
import com.sistemaspalacios.billing.service.WithholdingAuditQueryService.AuditRow;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Evidencia de la auditoría de retenciones en Excel (.xlsx). Números, porcentajes y fechas van como
 * celdas tipadas (no texto) para poder filtrar, sumar y ordenar; las horas se escriben en hora de
 * Colombia. Hojas: Retenciones (snapshot final), Historial (interacción) e Información (constancia
 * de la exportación: filtros, fecha y usuario).
 */
final class WithholdingAuditExcelWriter {

    private static final Map<String, String> CHANNELS = Map.of(
            "PAYMENT_GATEWAY", "Pasarela de pagos", "ADMIN_WEB", "Panel administrativo",
            "MANUAL_PAYMENT", "Registro manual", "API", "API");
    private static final Map<String, String> STATUSES = Map.of(
            "SELECTED", "Seleccionada (pago pendiente)", "NOT_APPLICABLE", "No aplica",
            "APPLIED", "Aplicada", "CANCELLED", "Anulada");
    private static final Map<String, String> EVENTS = Map.of(
            "SELECTED", "Seleccionó", "UPDATED", "Cambió el porcentaje", "REMOVED", "Quitó",
            "NOT_APPLICABLE", "No aplica", "APPLIED", "Aplicada", "CANCELLED", "Anulada");

    private WithholdingAuditExcelWriter() {}

    /** @param info pares (campo, valor) para la hoja Información, en orden. */
    static byte[] write(List<AuditRow> rows, List<AuditEventRow> events, List<String[]> info) {
        // Streaming: memoria acotada aunque se exporten decenas de miles de filas.
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            Styles styles = new Styles(workbook);
            writeWithholdings(workbook, styles, rows);
            writeEvents(workbook, styles, events);
            writeInfo(workbook, styles, info);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("No fue posible generar el Excel de auditoría", ex);
        }
    }

    private static void writeWithholdings(SXSSFWorkbook workbook, Styles s, List<AuditRow> rows) {
        String[] headers = {"Seleccionado", "Aplicado", "Estado selección", "Pago", "Estado pago", "NIT", "Cliente", "Facturas",
                "Concepto", "Tipo", "Versión concepto", "Vigente desde", "% ingresado", "% aplicado", "% máximo", "Base",
                "Base mínima", "Valor retenido", "Aplica", "Motivo no aplica", "Motivo ajuste", "Canal", "Actor", "Usuario",
                "Nombre usuario", "Sesión", "IP", "Agente de usuario"};
        int[] widths = {20, 20, 26, 38, 13, 15, 32, 26, 30, 14, 10, 13, 12, 12, 11, 16, 16, 16, 8, 34, 28, 20, 26, 18, 26, 38, 16, 40};
        SXSSFSheet sheet = sheet(workbook, s, "Retenciones", headers, widths);
        int r = 1;
        for (AuditRow row : rows) {
            Row x = sheet.createRow(r++);
            int c = 0;
            date(x, c++, row.selectedAt(), s);
            date(x, c++, row.appliedAt(), s);
            text(x, c++, STATUSES.getOrDefault(row.selectionStatus(), row.selectionStatus()));
            text(x, c++, str(row.paymentId()));
            text(x, c++, row.paymentStatus());
            text(x, c++, row.clientNit());
            text(x, c++, row.clientName());
            text(x, c++, String.join(", ", row.invoiceNumbers()));
            text(x, c++, row.conceptName());
            text(x, c++, row.conceptCode());
            number(x, c++, row.conceptVersion(), s.integer);
            day(x, c++, row.validFrom(), s);
            percent(x, c++, row.enteredPercentage(), s);
            percent(x, c++, row.appliedPercentage(), s);
            percent(x, c++, row.maxPercentage(), s);
            money(x, c++, row.calculationBase(), s);
            money(x, c++, row.minimumBase(), s);
            money(x, c++, row.withheldAmount(), s);
            text(x, c++, row.applicable() ? "Sí" : "No");
            text(x, c++, row.nonApplicableReason());
            text(x, c++, row.adjustmentReason());
            text(x, c++, row.sourceChannel() != null ? CHANNELS.getOrDefault(row.sourceChannel(), row.sourceChannel()) : "No registrado");
            text(x, c++, row.selectedBy().label());
            text(x, c++, row.selectedBy().username());
            text(x, c++, row.selectedBy().name());
            text(x, c++, row.selectedBy().sessionId());
            text(x, c++, row.ipAddress());
            text(x, c, row.userAgent());
        }
        finish(sheet, headers.length, r);
    }

    private static void writeEvents(SXSSFWorkbook workbook, Styles s, List<AuditEventRow> events) {
        String[] headers = {"Fecha", "Evento", "Concepto", "Tipo", "% anterior", "% nuevo", "Canal", "Actor", "Usuario", "Pago",
                "Factura", "Sesión", "IP", "Agente de usuario"};
        int[] widths = {20, 22, 30, 14, 12, 12, 20, 30, 18, 38, 38, 38, 16, 40};
        SXSSFSheet sheet = sheet(workbook, s, "Historial", headers, widths);
        int r = 1;
        for (AuditEventRow e : events) {
            Row x = sheet.createRow(r++);
            int c = 0;
            date(x, c++, e.occurredAt(), s);
            text(x, c++, EVENTS.getOrDefault(e.eventType(), e.eventType()));
            text(x, c++, e.conceptName());
            text(x, c++, e.conceptCode());
            percent(x, c++, e.oldPercentage(), s);
            percent(x, c++, e.newPercentage(), s);
            text(x, c++, CHANNELS.getOrDefault(e.sourceChannel(), e.sourceChannel()));
            text(x, c++, e.actor().label());
            text(x, c++, e.actor().username());
            text(x, c++, str(e.paymentId()));
            text(x, c++, str(e.invoiceId()));
            text(x, c++, e.sessionId());
            text(x, c++, e.ipAddress());
            text(x, c, e.userAgent());
        }
        finish(sheet, headers.length, r);
    }

    private static void writeInfo(SXSSFWorkbook workbook, Styles s, List<String[]> info) {
        Sheet sheet = workbook.createSheet("Información");
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 70 * 256);
        int r = 0;
        for (String[] pair : info) {
            Row row = sheet.createRow(r++);
            Cell key = row.createCell(0);
            key.setCellValue(pair[0]);
            key.setCellStyle(s.bold);
            row.createCell(1).setCellValue(pair[1] != null ? pair[1] : "");
        }
    }

    // ---------- utilidades ----------

    private static SXSSFSheet sheet(SXSSFWorkbook workbook, Styles s, String name, String[] headers, int[] widths) {
        SXSSFSheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(s.header);
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, 1);
        return sheet;
    }

    private static void finish(SXSSFSheet sheet, int columns, int rowCount) {
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rowCount - 1, 0), 0, columns - 1));
    }

    private static void text(Row row, int col, String value) {
        if (value != null && !value.isBlank()) row.createCell(col).setCellValue(value);
    }

    private static void number(Row row, int col, long value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** El porcentaje se guarda como lo escribió la persona (4 = 4%), no como fracción. */
    private static void percent(Row row, int col, BigDecimal value, Styles s) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(s.percent);
    }

    private static void money(Row row, int col, Money value, Styles s) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value.amount().doubleValue());
        cell.setCellStyle(s.money);
    }

    /** Hora de Colombia como fecha de Excel (sin zona: la hoja Información indica la zona). */
    private static void date(Row row, int col, OffsetDateTime value, Styles s) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value.toLocalDateTime());
        cell.setCellStyle(s.dateTime);
    }

    private static void day(Row row, int col, LocalDate value, Styles s) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(s.date);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle bold;
        final CellStyle money;
        final CellStyle percent;
        final CellStyle integer;
        final CellStyle dateTime;
        final CellStyle date;

        Styles(SXSSFWorkbook workbook) {
            var format = workbook.createDataFormat();
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);

            bold = workbook.createCellStyle();
            bold.setFont(boldFont);

            money = workbook.createCellStyle();
            money.setDataFormat(format.getFormat("\"$\" #,##0.00"));
            percent = workbook.createCellStyle();
            percent.setDataFormat(format.getFormat("0.####\"%\""));
            integer = workbook.createCellStyle();
            integer.setDataFormat(format.getFormat("0"));
            dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(format.getFormat("dd/mm/yyyy hh:mm:ss"));
            date = workbook.createCellStyle();
            date.setDataFormat(format.getFormat("dd/mm/yyyy"));
        }
    }
}
