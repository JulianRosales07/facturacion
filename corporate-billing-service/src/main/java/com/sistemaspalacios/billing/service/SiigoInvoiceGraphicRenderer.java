package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Generador de la representación gráfica oficial de la factura electrónica de venta
 * con diseño 100% fiel al documento PDF de referencia Siigo (F001000000519930000.PDF).
 */
@Component
public class SiigoInvoiceGraphicRenderer {

    private static final DecimalFormat MONEY_FORMAT;

    private static final String LOGO_BASE64;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        MONEY_FORMAT = new DecimalFormat("#,##0.00", symbols);

        String logo = null;
        try {
            java.io.File logoFile = new java.io.File("Sp.png");
            if (!logoFile.exists()) {
                logoFile = new java.io.File("src/main/resources/static/Sp.png");
            }
            if (logoFile.exists()) {
                byte[] bytes = java.nio.file.Files.readAllBytes(logoFile.toPath());
                logo = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes);
            }
        } catch (Exception e) {
            // fallback
        }
        LOGO_BASE64 = logo;
    }

    public String renderHtml(Invoice invoice) {
        return renderHtml(invoice, null);
    }

    /** Igual que {@link #renderHtml(Invoice)} más la sección "Conoce los cobros a detalle" si se informa. */
    public String renderHtml(Invoice invoice, com.sistemaspalacios.billing.service.dto.ChargeBreakdownDto breakdown) {
        String numFactura = documentNumber(invoice);
        LocalDate emision = invoice.getIssueDate() != null ? invoice.getIssueDate() : LocalDate.now();
        LocalDate vencimiento = invoice.getDueDate() != null ? invoice.getDueDate() : emision.plusDays(15);
        String emisionStr = emision.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String vencimientoStr = vencimiento.format(DateTimeFormatter.ISO_LOCAL_DATE);

        String clientNit = invoice.getClientNit();
        String clientName = invoice.getClientBusinessName() != null ? invoice.getClientBusinessName() : "-";
        // Nunca se completan datos con valores de otro cliente o de otra factura: lo que no se
        // conoce se informa como tal.
        String clientAddress = orNotInformed(invoice.getClientTaxAddress());
        String clientEmail = orNotInformed(invoice.getClientBillingEmail());
        String cufe = invoice.getCufe() != null && !invoice.getCufe().isBlank() ? invoice.getCufe() : CUFE_PENDING;

        StringBuilder itemsRows = new StringBuilder();
        List<InvoiceItem> items = invoice.getItems();
        for (int i = 0; i < items.size(); i++) {
            InvoiceItem it = items.get(i);
            String codigo = it.getProductCode() != null ? it.getProductCode() : "";
            itemsRows.append("<tr>")
                    .append("<td style=\"text-align:center;\">").append(i + 1).append("</td>")
                    .append("<td style=\"text-align:center; font-family:monospace;\">").append(escapeHtml(codigo)).append("</td>")
                    .append("<td style=\"word-break:break-word;\">").append(escapeHtml(it.getDescription())).append("</td>")
                    .append("<td style=\"text-align:center;\">").append(it.getQuantity().intValue()).append("</td>")
                    .append("<td style=\"text-align:center;\">UN</td>")
                    .append("<td style=\"text-align:right;\">").append(formatMoney(it.getUnitPrice().amount())).append("</td>")
                    .append("<td style=\"text-align:center;\">").append(it.getIvaPercentage().intValue()).append("</td>")
                    .append("<td style=\"text-align:right;\">").append(formatMoney(it.getIvaValue().amount())).append("</td>")
                    .append("<td style=\"text-align:right; font-weight:bold;\">").append(formatMoney(it.getTotalValue().amount())).append("</td>")
                    .append("</tr>");
        }

        BigDecimal totalBruto = invoice.getGrossAmount().amount();
        BigDecimal totalIva = invoice.getIvaAmount().amount();
        BigDecimal totalPagar = invoice.getTotalAmount().amount();
        String totalPagarStr = "$ " + formatMoney(totalPagar);
        String valorEnLetras = numberToSpanishWords(totalPagar.longValue());

        String qrBase64 = generateQrBase64(invoice.getQrContent());

        return buildHtmlDocument(numFactura, emisionStr, vencimientoStr, clientName, clientNit, clientAddress, clientEmail,
                cufe, itemsRows.toString(), items.size(), totalBruto, totalIva, totalPagar, totalPagarStr, valorEnLetras, qrBase64,
                breakdown != null ? renderBreakdown(breakdown) : "");
    }

    /** Bloques Deuda anterior / Cargos fijos / Otros conceptos, con el total de cada uno a la derecha. */
    private String renderBreakdown(com.sistemaspalacios.billing.service.dto.ChargeBreakdownDto b) {
        StringBuilder html = new StringBuilder();
        html.append("  <div style=\"background:#D9D9D9; font-weight:bold; text-align:center; font-size:9.5px; padding:3px; margin:6px 0 4px;\">")
                .append("ENTIÉNDELO MEJOR, CONOCE LOS COBROS A DETALLE</div>\n");

        var debt = b.deudaAnterior();
        String debtRows = "<tr><td>Cargos anteriores</td><td style=\"text-align:right;\">" + formatMoney(debt.cargosAnteriores()) + "</td></tr>"
                + "<tr><td>Pagos recibidos</td><td style=\"text-align:right;\">-" + formatMoney(debt.pagosRecibidos()) + "</td></tr>";
        html.append(breakdownBlock("DEUDA ANTERIOR", false, debtRows, debt.saldo()));

        html.append(breakdownBlock("CARGOS FIJOS", true, blockRows(b.cargosFijos(), true), b.cargosFijos().total()));
        html.append(breakdownBlock("OTROS CONCEPTOS", false, blockRows(b.otrosConceptos(), false), b.otrosConceptos().total()));

        html.append("  <div style=\"display:flex; justify-content:flex-end; margin-bottom:6px;\"><table style=\"width:280px; font-size:8.5px;\">")
                .append("<tr><td style=\"font-weight:bold;\">Total de esta factura</td><td style=\"text-align:right; font-weight:bold;\">")
                .append(formatMoney(b.totalFactura())).append("</td></tr>")
                .append("<tr style=\"background:#D9D9D9;\"><td style=\"font-weight:bold;\">Total con deuda anterior $</td><td style=\"text-align:right; font-weight:900;\">")
                .append(formatMoney(b.totalAPagar())).append("</td></tr></table></div>\n");
        return html.toString();
    }

    private String blockRows(com.sistemaspalacios.billing.service.dto.ChargeBreakdownDto.ChargeBlock block, boolean withDates) {
        StringBuilder rows = new StringBuilder();
        if (block.lineas().isEmpty()) {
            rows.append("<tr><td colspan=\"").append(withDates ? 4 : 2).append("\" style=\"color:#555;\">Sin conceptos</td></tr>");
        }
        String currentCategory = null;
        for (var line : block.lineas()) {
            // Otros conceptos va agrupado por subtítulo (novedades, cuotas, ajustes, cobranza).
            if (line.categoria() != null && !line.categoria().equals(currentCategory)) {
                currentCategory = line.categoria();
                rows.append("<tr><td colspan=\"").append(withDates ? 4 : 2).append("\" style=\"font-weight:bold;\">")
                        .append(escapeHtml(currentCategory.toUpperCase(Locale.ROOT))).append("</td></tr>");
            }
            rows.append("<tr><td").append(line.categoria() != null ? " style=\"padding-left:10px;\"" : "").append(">")
                    .append(escapeHtml(line.descripcion())).append("</td>");
            if (withDates) {
                rows.append("<td style=\"text-align:center;\">").append(line.fechaInicial() != null ? line.fechaInicial() : "—").append("</td>")
                        .append("<td style=\"text-align:center;\">").append(line.fechaFinal() != null ? line.fechaFinal() : "—").append("</td>");
            }
            rows.append("<td style=\"text-align:right;\">").append(formatMoney(line.valor())).append("</td></tr>");
        }
        rows.append("<tr><td colspan=\"").append(withDates ? 3 : 1).append("\">Impuestos</td><td style=\"text-align:right;\">")
                .append(formatMoney(block.impuestos())).append("</td></tr>");
        return rows.toString();
    }

    private String breakdownBlock(String title, boolean withDates, String rows, BigDecimal blockTotal) {
        String header = withDates
                ? "<tr style=\"font-weight:bold;\"><td>Descripción</td><td style=\"text-align:center; width:14%;\">Fecha inicial</td><td style=\"text-align:center; width:14%;\">Fecha final</td><td style=\"text-align:right; width:16%;\">Valor</td></tr>"
                : "<tr style=\"font-weight:bold;\"><td>Descripción</td><td style=\"text-align:right; width:16%;\">Valor</td></tr>";
        return "  <table style=\"margin-bottom:4px; font-size:8.5px;\">\n"
                + "    <tr><td colspan=\"2\" class=\"siigo-label-cell\" style=\"text-align:center;\">" + title + "</td></tr>\n"
                + "    <tr><td style=\"padding:0; border:none;\"><table style=\"border:none;\">" + header + rows + "</table></td>"
                + "<td style=\"width:18%; text-align:center; vertical-align:middle; font-weight:bold; font-size:10px;\">$ "
                + formatMoney(blockTotal) + "</td></tr>\n"
                + "  </table>\n";
    }

    public String renderGroupHtml(IncomingInvoiceQueryService.ClientInvoiceGroup group) {
        String numFactura;
        if (group.invoices().size() == 1) {
            var f = group.invoices().get(0);
            numFactura = (f.billingType() == com.sistemaspalacios.billing.domain.model.BillingType.F20 ? "FEMG " : "FESP ") + f.invoiceNumber();
        } else if (!group.invoices().isEmpty()) {
            var f = group.invoices().get(0);
            numFactura = (f.billingType() == com.sistemaspalacios.billing.domain.model.BillingType.F20 ? "FEMG " : "FESP ") + f.invoiceNumber()
                    + " (+" + (group.invoiceCount() - 1) + " Facturas)";
        } else {
            numFactura = "FESP CONSOLIDADO";
        }

        LocalDate emision = (!group.invoices().isEmpty() && group.invoices().get(0).issueDate() != null)
                ? group.invoices().get(0).issueDate() : LocalDate.now();
        LocalDate vencimiento = (!group.invoices().isEmpty() && group.invoices().get(0).dueDate() != null)
                ? group.invoices().get(0).dueDate() : emision.plusDays(15);
        String emisionStr = emision.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String vencimientoStr = vencimiento.format(DateTimeFormatter.ISO_LOCAL_DATE);

        String clientNit = group.nit();
        String clientName = group.businessName() != null ? group.businessName() : ("NIT " + group.nit());
        String clientAddress = NOT_INFORMED;
        String clientEmail = NOT_INFORMED;
        String cufe = (!group.invoices().isEmpty() && group.invoices().get(0).cufe() != null)
                ? group.invoices().get(0).cufe()
                : CUFE_PENDING;

        String firstQrContent = group.invoices().stream()
                .filter(inv -> inv.qr() != null && inv.qr().content() != null && !inv.qr().content().isBlank())
                .map(inv -> inv.qr().content())
                .findFirst()
                .orElse(null);
        String qrBase64 = generateQrBase64(firstQrContent);

        StringBuilder itemsRows = new StringBuilder();
        BigDecimal totalBruto = BigDecimal.ZERO;
        BigDecimal totalIva = BigDecimal.ZERO;
        var facturas = group.invoices();
        for (int i = 0; i < facturas.size(); i++) {
            var inv = facturas.get(i);
            String codigo = "";
            BigDecimal totalItem = inv.serviceValue() != null ? inv.serviceValue() : BigDecimal.ZERO;
            BigDecimal unitPrice = totalItem.divide(new BigDecimal("1.19"), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal ivaVal = totalItem.subtract(unitPrice);
            totalBruto = totalBruto.add(unitPrice);
            totalIva = totalIva.add(ivaVal);

            String desc = "Factura " + inv.billingType() + " " + inv.invoiceNumber() + " - Servicios corporativos y conectividad";
            itemsRows.append("<tr>")
                    .append("<td style=\"text-align:center;\">").append(i + 1).append("</td>")
                    .append("<td style=\"text-align:center; font-family:monospace;\">").append(codigo).append("</td>")
                    .append("<td style=\"word-break:break-word;\">").append(escapeHtml(desc)).append("</td>")
                    .append("<td style=\"text-align:center;\">1</td>")
                    .append("<td style=\"text-align:center;\">UN</td>")
                    .append("<td style=\"text-align:right;\">").append(formatMoney(unitPrice)).append("</td>")
                    .append("<td style=\"text-align:center;\">19</td>")
                    .append("<td style=\"text-align:right;\">").append(formatMoney(ivaVal)).append("</td>")
                    .append("<td style=\"text-align:right; font-weight:bold;\">").append(formatMoney(totalItem)).append("</td>")
                    .append("</tr>");
        }

        BigDecimal totalPagar = group.totalServiceValue() != null ? group.totalServiceValue() : BigDecimal.ZERO;
        String totalPagarStr = "$ " + formatMoney(totalPagar);
        String valorEnLetras = numberToSpanishWords(totalPagar.longValue());

        return buildHtmlDocument(numFactura, emisionStr, vencimientoStr, clientName, clientNit, clientAddress, clientEmail,
                cufe, itemsRows.toString(), facturas.size(), totalBruto, totalIva, totalPagar, totalPagarStr, valorEnLetras, qrBase64, "");
    }

    private String generateQrBase64(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            var matrix = writer.encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 200, 200);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            com.google.zxing.client.j2se.MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private String buildHtmlDocument(String numFactura, String emisionStr, String vencimientoStr,
                                     String clientName, String clientNit, String clientAddress, String clientEmail,
                                     String cufe, String itemsRows, int totalItems,
                                     BigDecimal totalBruto, BigDecimal totalIva, BigDecimal totalPagar,
                                     String totalPagarStr, String valorEnLetras, String qrBase64, String breakdownHtml) {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"es\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <title>Factura Electrónica de Venta - " + escapeHtml(numFactura) + "</title>\n" +
                "  <style>\n" +
                "    body { margin: 0; padding: 20px; background: #525659; font-family: Arial, Helvetica, sans-serif; display: flex; justify-content: center; }\n" +
                "    .siigo-invoice-sheet {\n" +
                "      position: relative; width: 816px; min-height: 1020px; box-sizing: border-box; background: #FFFFFF; color: #000;\n" +
                "      font-size: 8.5px; line-height: 1.25; padding: 20px 24px 20px 22px; box-shadow: 0 4px 25px rgba(0,0,0,0.35);\n" +
                "    }\n" +
                "    table { border-collapse: collapse; width: 100%; color: #000; }\n" +
                "    th, td { border: 1px solid #000; padding: 2px 4px; }\n" +
                "    .siigo-label-cell { background-color: #D9D9D9 !important; font-weight: bold; }\n" +
                "    .siigo-vertical-sidebar {\n" +
                "      position: absolute; right: 4px; top: 220px; writing-mode: vertical-rl; font-size: 7.2px; color: #777; letter-spacing: 0.2px; font-family: Arial, sans-serif;\n" +
                "    }\n" +
                "    @media print {\n" +
                "      @page { size: letter portrait; margin: 0; }\n" +
                "      body { background: #FFF !important; padding: 0 !important; margin: 0 !important; }\n" +
                "      .siigo-invoice-sheet { box-shadow: none !important; margin: 0 !important; width: 100% !important; height: 100% !important; padding: 20px 24px 20px 22px !important; }\n" +
                "      .no-print { display: none !important; }\n" +
                "    }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"siigo-invoice-sheet\">\n" +
                "  <div class=\"siigo-vertical-sidebar\">Software:SIIGO, Desarrollado por: SIIGO SAS Nit 830.048.145-8, PT: SIIGO SAS Nit: 830.048.145-8</div>\n" +
                "  <div style=\"display:grid; grid-template-columns: 26% 46% 28%; gap: 6px; align-items: center; margin-bottom: 6px;\">\n" +
                "    <div style=\"text-align:center;\">\n" +
                (LOGO_BASE64 != null
                        ? "      <img src=\"" + LOGO_BASE64 + "\" alt=\"Sistemas Palacios\" style=\"max-width:145px; max-height:55px; object-fit:contain;\" />\n"
                        : "      <div style=\"font-size:16px; font-weight:900; color:#E32636; letter-spacing:0.5px;\">SISTEMAS PALACIOS</div>\n" +
                          "      <div style=\"font-size:7.5px; font-weight:bold; color:#000; letter-spacing:0.5px;\">IPT ES INTERNET PARA TODOS</div>\n") +
                "    </div>\n" +
                "    <div style=\"text-align:center; font-size:8.5px; line-height:1.2;\">\n" +
                "      <div style=\"font-weight:bold; font-size:10.5px;\">SP SISTEMAS PALACIOS LTDA</div>\n" +
                "      <div>NIT : 900,163,149 - 2</div>\n" +
                "      <div>CL 2 22 75 BRR BACHUE</div>\n" +
                "      <div>PASTO-NARIÑO - COLOMBIA</div>\n" +
                "      <div>3104005873</div>\n" +
                "      <div>contabilidad@sistemaspalacios.com</div>\n" +
                "      <div>E</div>\n" +
                "      <div>Actividad Económica 313 Tarifa Ica 6 por mil</div>\n" +
                "    </div>\n" +
                "    <div style=\"display:flex; flex-direction:column; align-items:center;\">\n" +
                (qrBase64 != null
                        ? "      <img src=\"" + qrBase64 + "\" alt=\"QR Oficial DIAN\" style=\"width:52px; height:52px; object-fit:contain; border:1px solid #000; padding:1px; margin-bottom:2px;\" />\n"
                        : "      <div style=\"width:50px; height:50px; border:1px dashed #000; font-size:6.5px; display:flex; align-items:center; justify-content:center; text-align:center; margin-bottom:2px;\">QR no disponible</div>\n") +
                "      <div style=\"border:1px solid #000; width:100%; padding:2px 4px; text-align:center; font-size:8px;\">\n" +
                "        <div style=\"font-weight:bold; font-size:8.5px;\">FACTURA ELECTRÓNICA DE VENTA</div>\n" +
                "        <div style=\"font-size:12px; font-weight:900; margin:1px 0;\">" + escapeHtml(numFactura) + "</div>\n" +
                "        <div style=\"font-weight:bold; font-size:7.5px;\">Fecha y Hora de Factura</div>\n" +
                "        <div>Emisión " + emisionStr + "</div>\n" +
                "        <div>Vencimiento " + vencimientoStr + "</div>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <table style=\"margin-bottom:5px; font-size:8.5px;\">\n" +
                "    <tr><td class=\"siigo-label-cell\" style=\"width:14%;\">Cliente</td><td colspan=\"5\" style=\"font-weight:bold;\">" + escapeHtml(clientName) + "</td></tr>\n" +
                "    <tr><td class=\"siigo-label-cell\">Nit / Ced.</td><td style=\"width:25%; font-weight:600;\">" + escapeHtml(clientNit) + "</td><td class=\"siigo-label-cell\" style=\"width:18%;\">/Id. Extranjero :</td><td style=\"width:14%;\"></td><td class=\"siigo-label-cell\" style=\"width:14%;\">Teléfono</td><td style=\"width:15%;\"></td></tr>\n" +
                "    <tr><td class=\"siigo-label-cell\">Dirección</td><td colspan=\"3\">" + escapeHtml(clientAddress) + "</td><td class=\"siigo-label-cell\">Vendedor</td><td></td></tr>\n" +
                "    <tr><td class=\"siigo-label-cell\">Ciudad</td><td colspan=\"3\"></td><td class=\"siigo-label-cell\">Centro Costo</td><td></td></tr>\n" +
                "    <tr><td class=\"siigo-label-cell\">Correo</td><td colspan=\"3\">" + escapeHtml(clientEmail) + "</td><td class=\"siigo-label-cell\">Estrato</td><td></td></tr>\n" +
                "    <tr><td class=\"siigo-label-cell\">Order Reference Prefijo</td><td colspan=\"3\" class=\"siigo-label-cell\"></td><td class=\"siigo-label-cell\">Número</td><td></td></tr>\n" +
                "  </table>\n" +
                "  <table style=\"margin-bottom:2px; font-size:8.5px;\">\n" +
                "    <thead>\n" +
                "      <tr style=\"background:#D9D9D9; font-weight:bold; text-align:center;\">\n" +
                "        <th style=\"width:4%;\">Item</th><th style=\"width:14%;\">Codigo</th><th style=\"width:37%; text-align:left;\">Descripcion</th>\n" +
                "        <th style=\"width:5%;\">Cant</th><th style=\"width:5%;\">Und</th><th style=\"width:11%; text-align:right;\">V. Unit.</th>\n" +
                "        <th style=\"width:5%;\">%Iva</th><th style=\"width:9%; text-align:right;\">Vr.Iva</th><th style=\"width:15%; text-align:right;\">Vr.Total incluido Iva</th>\n" +
                "      </tr>\n" +
                "    </thead>\n" +
                "    <tbody>\n" +
                itemsRows +
                "    </tbody>\n" +
                "  </table>\n" +
                "  <div style=\"font-weight:bold; font-size:8.5px; margin-bottom:4px;\">Total Items : " + totalItems + "</div>\n" +
                "  <div style=\"display:flex; justify-content:flex-end; margin-bottom:6px;\">\n" +
                "    <table style=\"width:280px; font-size:8.5px;\">\n" +
                "      <tr><td style=\"font-weight:bold; width:52%;\">Total Bruto</td><td style=\"text-align:right; font-weight:bold;\">" + formatMoney(totalBruto) + "</td></tr>\n" +
                "      <tr><td style=\"font-weight:bold;\">IVA</td><td style=\"text-align:right; font-weight:bold;\">" + formatMoney(totalIva) + "</td></tr>\n" +
                "      <tr style=\"background:#D9D9D9;\"><td style=\"font-weight:bold; font-size:9.5px;\">Total A Pagar $</td><td style=\"text-align:right; font-weight:900; font-size:10.5px;\">" + totalPagarStr + "</td></tr>\n" +
                "    </table>\n" +
                "  </div>\n" +
                breakdownHtml +
                "  <div style=\"font-size:8px; border-bottom:1px solid #000; padding-bottom:3px; margin-bottom:3px;\">\n" +
                "    <span style=\"font-weight:bold;\">FORMA DE PAGO</span> &nbsp; Credito Cr-Cartera Zona Pasto Medio de Pago : Otro* " + totalPagarStr + " Cuota 1 Vence el " + vencimientoStr + "\n" +
                "  </div>\n" +
                "  <div style=\"font-size:8px; border-bottom:1px solid #000; padding-bottom:3px; margin-bottom:4px;\">\n" +
                "    <span style=\"font-weight:bold;\">VALOR EN LETRAS</span> &nbsp; <span style=\"font-weight:bold;\">" + valorEnLetras + " Pesos M/Cte</span>\n" +
                "  </div>\n" +
                "  <div style=\"font-size:7.5px; font-weight:bold; margin-bottom:8px; line-height:1.25;\">\n" +
                "    OBSERVACIONES: Realice su pago oportuno por medio de los siguientes canales de pago: convenio Bancolombia #31689 o transferencia bancaria a la cuenta de ahorros Bancolombia #99784883228 a nombre de SP SISTEMAS PALACIOS LTDA y envie su soporte al correo cartera@sistemaspalacios.com\n" +
                "  </div>\n" +
                "  <div style=\"display:flex; justify-content:space-around; margin-top:20px; margin-bottom:8px; font-size:8px; text-align:center;\">\n" +
                "    <div style=\"width:38%;\"><div style=\"border-top:1px solid #000; padding-top:2px;\">Generado por el sistema de facturación</div></div>\n" +
                "    <div style=\"width:38%;\"><div style=\"border-top:1px solid #000; padding-top:2px;\">Firma Recibido</div></div>\n" +
                "  </div>\n" +
                "  <div style=\"text-align:center; font-weight:bold; font-size:7px; margin-bottom:4px;\">\n" +
                "    En caso de requerir retiro de sus servicios, tenga en cuenta que su solicitud la debe realizar como mínimo con tres días hábiles a su ultimo periodo facturado, esta misma debe ser enviada al correo contabilidad@sistemaspalacios.com\n" +
                "  </div>\n" +
                "  <div style=\"border:1px solid #000; padding:3px 5px; font-size:7px; text-align:justify; line-height:1.2; margin-bottom:3px;\">\n" +
                "    A esta factura de venta aplican las normas relativas a la letra de cambio (artículo 5 Ley 1231 de 2008). Con esta el Comprador declara haber recibido real y materialmente las mercancías o prestación de servicios descritos en este título - Valor. Autorización de facturación No. 18764093952604 aprobado en 2025-06-03 vigente 2 Años, prefijo FESP desde el número 35001 al 70000\n" +
                "  </div>\n" +
                "  <div style=\"text-align:center; font-weight:bold; font-family:Courier, monospace; font-size:7.5px; word-break:break-all; margin-bottom:4px;\">\n" +
                "    CUFE: " + escapeHtml(cufe) + "\n" +
                "  </div>\n" +
                "  <div style=\"display:flex; justify-content:space-between; font-size:7.5px; border-top:1px solid #ccc; padding-top:2px;\">\n" +
                "    <span>Representación Gráfica de la Factura Electrónica de Venta</span>\n" +
                "    <span>Pagina : 1 de 1</span>\n" +
                "  </div>\n" +
                "</div>\n" +
                "</body>\n" +
                "</html>";
    }

    static final String NOT_INFORMED = "No informado";
    static final String CUFE_PENDING = "Pendiente de validación DIAN";

    private static String orNotInformed(String value) {
        return value != null && !value.isBlank() ? value : NOT_INFORMED;
    }

    /** FESP 51980 / FEMG 287: prefijo según el tipo de facturación, sin duplicarlo si ya viene en el número. */
    static String documentNumber(Invoice invoice) {
        String number = invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber().trim() : "";
        if (number.isEmpty()) return "SIN NÚMERO";
        if (!Character.isDigit(number.charAt(0))) return number;
        return (invoice.getBillingType() == com.sistemaspalacios.billing.domain.model.BillingType.F20 ? "FEMG " : "FESP ") + number;
    }

    public byte[] renderPdf(Invoice invoice) {
        return renderHtmlToPdf(renderHtml(invoice));
    }

    public byte[] renderPdf(Invoice invoice, com.sistemaspalacios.billing.service.dto.ChargeBreakdownDto breakdown) {
        return renderHtmlToPdf(renderHtml(invoice, breakdown));
    }

    public byte[] renderGroupPdf(IncomingInvoiceQueryService.ClientInvoiceGroup group) {
        return renderHtmlToPdf(renderGroupHtml(group));
    }

    public byte[] renderHtmlToPdf(String html) {
        try {
            java.nio.file.Path tempHtml = java.nio.file.Files.createTempFile("siigo_invoice_", ".html");
            java.nio.file.Path tempPdf = java.nio.file.Files.createTempFile("siigo_invoice_", ".pdf");
            try {
                java.nio.file.Files.writeString(tempHtml, html, java.nio.charset.StandardCharsets.UTF_8);

                String browserPath = null;
                String[] candidatePaths = {
                        "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                        "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                        "/usr/bin/chromium",
                        "/usr/bin/chromium-browser",
                        "/usr/bin/google-chrome"
                };
                for (String cp : candidatePaths) {
                    if (new java.io.File(cp).exists()) {
                        browserPath = cp;
                        break;
                    }
                }

                if (browserPath != null) {
                    ProcessBuilder pb = new ProcessBuilder(
                            browserPath,
                            "--headless=new",
                            "--disable-gpu",
                            "--no-sandbox",
                            "--disable-dev-shm-usage",
                            "--no-pdf-header-footer",
                            "--print-to-pdf=" + tempPdf.toAbsolutePath(),
                            tempHtml.toUri().toString()
                    );
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                    if (finished && process.exitValue() == 0 && java.nio.file.Files.exists(tempPdf) && java.nio.file.Files.size(tempPdf) > 0) {
                        return java.nio.file.Files.readAllBytes(tempPdf);
                    }
                }
            } finally {
                java.nio.file.Files.deleteIfExists(tempHtml);
                java.nio.file.Files.deleteIfExists(tempPdf);
            }
        } catch (Exception e) {
            // Fallback to HTML bytes if headless printing fails
        }
        return html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String formatMoney(BigDecimal amount) {
        if (amount == null) return "0.00";
        return MONEY_FORMAT.format(amount);
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String numberToSpanishWords(long n) {
        if (n == 0) return "Cero";
        if (n < 0) return "Menos " + numberToSpanishWords(Math.abs(n));

        String[] units = {"", "Un", "Dos", "Tres", "Cuatro", "Cinco", "Seis", "Siete", "Ocho", "Nueve",
                "Diez", "Once", "Doce", "Trece", "Catorce", "Quince", "Dieciséis", "Diecisiete", "Dieciocho", "Diecinueve", "Veinte",
                "Veintiuno", "Veintidós", "Veintitrés", "Veinticuatro", "Veinticinco", "Veintiséis", "Veintisiete", "Veintiocho", "Veintinueve"};
        String[] tens = {"", "", "", "Treinta", "Cuarenta", "Cincuenta", "Sesenta", "Setenta", "Ochenta", "Noventa"};
        String[] hundreds = {"", "Ciento", "Doscientos", "Trescientos", "Cuatrocientos", "Quinientos", "Seiscientos", "Setecientos", "Ochocientos", "Novecientos"};

        if (n == 100) return "Cien";
        if (n < 30) return units[(int) n];
        if (n < 100) {
            int t = (int) (n / 10);
            int u = (int) (n % 10);
            return tens[t] + (u > 0 ? " Y " + units[u] : "");
        }
        if (n < 1000) {
            int h = (int) (n / 100);
            long rem = n % 100;
            return hundreds[h] + (rem > 0 ? " " + numberToSpanishWords(rem) : "");
        }
        if (n < 1_000_000) {
            long thousands = n / 1000;
            long rem = n % 1000;
            String prefix = (thousands == 1) ? "Mil" : numberToSpanishWords(thousands) + " Mil";
            return prefix + (rem > 0 ? " " + numberToSpanishWords(rem) : "");
        }
        if (n < 1_000_000_000) {
            long millions = n / 1_000_000;
            long rem = n % 1_000_000;
            String prefix = (millions == 1) ? "Un Millón" : numberToSpanishWords(millions) + " Millones";
            return prefix + (rem > 0 ? " " + numberToSpanishWords(rem) : "");
        }
        return String.valueOf(n);
    }
}
