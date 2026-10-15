package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.service.ReportService;
import com.sistemaspalacios.billing.service.dto.NicBillingReportResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Controller for billing reports (HU-59).
 */
@RestController
@RequestMapping("/api/v1/reportes")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = Objects.requireNonNull(reportService, "reportService cannot be null");
    }

    /**
     * HU-59: Total facturado por NIC para mayoristas/revendedores.
     * GET /api/v1/reportes/nic?periodo=2026-08
     */
    @GetMapping("/nic")
    @PreAuthorize("hasAuthority('REPORTS_VIEW')")
    public ResponseEntity<NicBillingReportResponse> getBillingByNic(@RequestParam(name = "periodo") String periodo) {
        if (periodo == null || periodo.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        NicBillingReportResponse response = reportService.generateNicBillingReport(periodo);
        return ResponseEntity.ok(response);
    }
}
