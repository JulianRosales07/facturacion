package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.SyncStatusEntity;
import com.sistemaspalacios.billing.service.OrderService;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncResultDto;
import com.sistemaspalacios.billing.service.siigo.ScheduledSiigoSyncJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * REST controller for System and Siigo synchronization (Sprint 3, Sprint 8 HU-35, Sprint 9 HU-56).
 * Supports scheduled/manual Order sync and hourly Siigo shared folder processing.
 */
@RestController
@RequestMapping({"/api/v1/sincronizacion", "/sincronizacion"})
public class SyncController {

    private final OrderService orderService;
    private final ScheduledSiigoSyncJob scheduledSiigoSyncJob;
    private final com.sistemaspalacios.billing.service.ClientNoveltyService clientNoveltyService;

    @Autowired
    public SyncController(
            OrderService orderService,
            @Autowired(required = false) ScheduledSiigoSyncJob scheduledSiigoSyncJob,
            com.sistemaspalacios.billing.service.ClientNoveltyService clientNoveltyService
    ) {
        this.orderService = Objects.requireNonNull(orderService, "orderService cannot be null");
        this.scheduledSiigoSyncJob = scheduledSiigoSyncJob;
        this.clientNoveltyService = Objects.requireNonNull(clientNoveltyService, "clientNoveltyService cannot be null");
    }

    /**
     * Consulta el resumen y detalle de novedades de órdenes para un cliente en un periodo contable.
     */
    @GetMapping("/novedades/clientes/{nit}")
    @PreAuthorize("hasAnyAuthority('SYNC_VIEW', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<com.sistemaspalacios.billing.service.dto.ClientNoveltiesSummaryDto> getClientNovelties(
            @PathVariable("nit") String nit,
            @RequestParam(name = "periodo", required = false) String periodo
    ) {
        com.sistemaspalacios.billing.service.dto.ClientNoveltiesSummaryDto summary =
                clientNoveltyService.getClientNoveltiesSummary(nit, periodo);
        return ResponseEntity.ok(summary);
    }

    /**
     * Consume y sincroniza las novedades de órdenes para un cliente:
     * Identifica si el cliente ha adquirido nuevos servicios (altas),
     * ha sufrido cambios contractuales (cambio de plan, upgrades/downgrades, traslados)
     * o adiciones en sus servicios (cuotas de instalación, etc.).
     */
    @PostMapping("/consumir-novedades")
    @PreAuthorize("hasAnyAuthority('SYNC_EXECUTE', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<com.sistemaspalacios.billing.service.dto.ClientNoveltiesSummaryDto> consumeClientNovelties(
            @RequestBody com.sistemaspalacios.billing.web.dto.ConsumirNovedadesRequest request
    ) {
        com.sistemaspalacios.billing.service.dto.ClientNoveltiesSummaryDto summary =
                clientNoveltyService.consumeAndSyncClientNovelties(request.nit(), request.periodo());
        return ResponseEntity.ok(summary);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('SYNC_EXECUTE', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<OrderSyncResultDto> syncPeriod(
            @RequestParam(name = "periodo", required = false) String periodCode
    ) {
        String effectivePeriod = (periodCode != null && !periodCode.isBlank())
                ? periodCode.trim()
                : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        OrderSyncResultDto result = orderService.syncPeriod(effectivePeriod);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/estado")
    @PreAuthorize("hasAnyAuthority('SYNC_VIEW', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<Map<String, Object>> getSyncStatus() {
        Optional<SyncStatusEntity> lastRun = scheduledSiigoSyncJob != null
                ? scheduledSiigoSyncJob.getLatestStatus()
                : Optional.empty();

        boolean isRunning = scheduledSiigoSyncJob != null && scheduledSiigoSyncJob.isCurrentlyRunning();

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("enEjecucion", isRunning);
        result.put("ultimoCiclo", lastRun.orElse(null));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/ahora")
    @PreAuthorize("hasAnyAuthority('SYNC_EXECUTE', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<SyncStatusEntity> triggerManualSync() {
        if (scheduledSiigoSyncJob == null) {
            return ResponseEntity.badRequest().build();
        }
        SyncStatusEntity status = scheduledSiigoSyncJob.triggerManualSync();
        return ResponseEntity.ok(status);
    }
}
