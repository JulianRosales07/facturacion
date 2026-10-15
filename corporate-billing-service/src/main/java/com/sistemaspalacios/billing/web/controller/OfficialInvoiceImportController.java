package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.service.IncomingFileIntakeService;
import com.sistemaspalacios.billing.service.IncomingFileAuditService;
import com.sistemaspalacios.billing.service.IncomingInvoiceQueryService;
import com.sistemaspalacios.billing.domain.model.IncomingFileHistory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/siigo/facturas")
public class OfficialInvoiceImportController {
    private final IncomingFileIntakeService importer;
    private final IncomingFileAuditService audit;
    public OfficialInvoiceImportController(IncomingFileIntakeService importer, IncomingFileAuditService audit) { this.importer = importer; this.audit = audit; }
    @GetMapping("/historial")
    @PreAuthorize("hasAnyAuthority('IMPORTS_VIEW', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public IncomingInvoiceQueryService.PageResult<IncomingFileHistory> history(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return audit.history(page,size);
    }
    @PostMapping(value = "/importar", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyAuthority('IMPORTS_EXECUTE', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public List<UUID> importFile(@RequestParam("archivo") MultipartFile file,
            @RequestParam(required = false) java.time.Instant fechaCorte) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Archivo vacío");
        try (var input = file.getInputStream()) {
            var result = importer.receive(file.getOriginalFilename(), input.readAllBytes(), "MANUAL", fechaCorte).invoiceIds();
            if (result.isEmpty()) throw new IllegalArgumentException("El archivo no contiene facturas F1/F20 en un formato compatible");
            return result;
        }
    }

    @PostMapping(value = "/importar-detallado", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyAuthority('IMPORTS_EXECUTE', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public com.sistemaspalacios.billing.service.OfficialInvoiceImportService.ImportResult importDetailed(
            @RequestParam("archivo") MultipartFile file,
            @RequestParam(required = false) java.time.Instant fechaCorte) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Archivo vacío");
        return importer.receive(file.getOriginalFilename(), file.getBytes(), "MANUAL", fechaCorte);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sistemaspalacios.billing.service.OfficialInvoiceDetailService detailService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sistemaspalacios.billing.service.InvoiceAvailabilityService availabilityService;

    /**
     * Auditoría de disponibilidad mensual: evalúa el estado de las facturas del periodo
     * (a tiempo, con retraso, incidencia de factura faltante, o regla corporativa especial)
     * sin inventar documentos ficticios.
     */
    @GetMapping("/disponibilidad-mensual")
    @PreAuthorize("hasAnyAuthority('IMPORTS_VIEW', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public java.util.List<com.sistemaspalacios.billing.service.InvoiceAvailabilityService.ClientPeriodAvailabilityReport> auditAvailability(
            @RequestParam String periodo,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fechaEvaluacion
    ) {
        if (availabilityService == null) return java.util.List.of();
        java.time.YearMonth ym = java.time.YearMonth.parse(periodo.trim());
        java.time.LocalDate evalDate = fechaEvaluacion != null ? fechaEvaluacion : java.time.LocalDate.now();
        return availabilityService.evaluateAllActiveClients(ym, evalDate);
    }

    /**
     * Reintento seguro: vuelve a leer los PDF ya recibidos de los documentos de Siigo que siguen
     * con el concepto genérico del corte de cartera y les aplica su detalle. Idempotente; el
     * resultado de cada documento queda en el historial de archivos.
     */
    @PostMapping("/aplicar-detalle-pdf")
    @PreAuthorize("hasAnyAuthority('IMPORTS_EXECUTE', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public java.util.Map<String, Integer> reapplyPdfDetail() {
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        if (detailService != null) {
            detailService.reapplyStoredPdfs().forEach((outcome, count) -> result.put(outcome.name(), count));
        }
        return result;
    }
}
