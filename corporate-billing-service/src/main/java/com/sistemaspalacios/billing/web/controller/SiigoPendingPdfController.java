package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.service.IncomingInvoiceQueryService.PageResult;
import com.sistemaspalacios.billing.service.SiigoPdfImporterService;
import com.sistemaspalacios.billing.service.dto.PendingPdfResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint para consultar los archivos PDF recibidos que aún no han sido asociados a una factura.
 */
@RestController
@RequestMapping("/api/v1/siigo/pdf-pendientes")
@PreAuthorize("hasAuthority('INVOICES_VIEW')")
public class SiigoPendingPdfController {

    private final SiigoPdfImporterService pdfImporterService;

    public SiigoPendingPdfController(SiigoPdfImporterService pdfImporterService) {
        this.pdfImporterService = pdfImporterService;
    }

    @GetMapping
    public PageResult<PendingPdfResponse> getPendingPdfs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return pdfImporterService.getPendingPdfs(page, size);
    }
}
