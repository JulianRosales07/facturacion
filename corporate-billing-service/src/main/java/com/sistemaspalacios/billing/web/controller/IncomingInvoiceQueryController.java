package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.service.IncomingInvoiceQueryService;
import com.sistemaspalacios.billing.service.SiigoInvoiceGraphicRenderer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/facturas/recibidas")
@PreAuthorize("hasAnyAuthority('INVOICES_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
public class IncomingInvoiceQueryController {
    private final IncomingInvoiceQueryService service;
    private final SiigoInvoiceGraphicRenderer graphicRenderer;

    public IncomingInvoiceQueryController(IncomingInvoiceQueryService service, SiigoInvoiceGraphicRenderer graphicRenderer) {
        this.service = service;
        this.graphicRenderer = graphicRenderer;
    }

    @GetMapping
    public IncomingInvoiceQueryService.PageResult<IncomingInvoiceQueryService.InvoiceRow> search(
            @RequestParam(required=false) String search, @RequestParam(required=false) String number,
            @RequestParam(required=false) BillingType type, @RequestParam(required=false) String period,
            @RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) Boolean pdfAvailable,
            @RequestParam(required=false) String estadoPago,
            @RequestParam(required=false, defaultValue="true") Boolean includePaid,
            @RequestParam(required=false) String origen,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return service.search(search, number, type, period, from, to, pdfAvailable, estadoPago, includePaid, origen, page, size);
    }

    /** Igual que {@link #search} pero un objeto por NIT con sus facturas, estado de pago y montos total, pagado y pendiente. */
    @GetMapping("/por-nit")
    public IncomingInvoiceQueryService.PageResult<IncomingInvoiceQueryService.ClientInvoiceGroup> searchGroupedByNit(
            @RequestParam(required=false) String search, @RequestParam(required=false) String number,
            @RequestParam(required=false) BillingType type, @RequestParam(required=false) String period,
            @RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) Boolean pdfAvailable,
            @RequestParam(required=false) String estadoPago,
            @RequestParam(required=false, defaultValue="false") Boolean includePaid,
            @RequestParam(required=false) String origen,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return service.searchGroupedByNit(search, number, type, period, from, to, pdfAvailable, estadoPago, includePaid, origen, page, size);
    }

    /** Obtiene directamente el objeto JSON consolidado con facturas pendientes y QR para un NIT específico. */
    @GetMapping(value = "/por-nit/{nit}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IncomingInvoiceQueryService.ClientInvoiceGroup> getConsolidatedJsonByNit(@PathVariable String nit) {
        return service.findGroupByNit(nit)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Consulta el historial completo de facturas de un NIT (tanto pendientes como pagadas),
     * detallando el identificador de transacción, medio de pago, fecha de pago y estado.
     */
    @GetMapping(value = "/por-nit/{nit}/historial", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.List<IncomingInvoiceQueryService.InvoiceHistoryItem>> getInvoiceHistoryByNit(@PathVariable String nit) {
        return ResponseEntity.ok(service.getInvoiceHistoryByNit(nit));
    }

    /** Representación gráfica HTML fiel al diseño del PDF Siigo F001000000519930000.PDF del consolidado por NIT. */
    @GetMapping(value = "/por-nit/{nit}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getConsolidatedHtml(@PathVariable String nit) {
        return service.findGroupByNit(nit)
                .map(graphicRenderer::renderGroupHtml)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Documento PDF oficial generado fiel al diseño del PDF Siigo F001000000519930000.PDF del consolidado por NIT. */
    @GetMapping(value = "/por-nit/{nit}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getConsolidatedPdf(@PathVariable String nit) {
        var groupOpt = service.findGroupByNit(nit);
        if (groupOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var group = groupOpt.get();
        byte[] pdfBytes = graphicRenderer.renderGroupPdf(group);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("factura-consolidada-NIT-" + group.nit() + ".pdf", java.nio.charset.StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    @GetMapping("/resumen")
    public IncomingInvoiceQueryService.Summary summary() { return service.summary(); }
}
