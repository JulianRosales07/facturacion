package com.sistemaspalacios.billing.web.controller.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sistemaspalacios.billing.domain.model.BillingIncident;
import com.sistemaspalacios.billing.domain.model.BillingIncidentStatus;
import com.sistemaspalacios.billing.domain.model.BillingIncidentType;
import com.sistemaspalacios.billing.domain.model.ClientBillingRule;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.RecordStatus;
import com.sistemaspalacios.billing.service.BillingIncidentService;
import com.sistemaspalacios.billing.service.ClientBillingRuleService;
import com.sistemaspalacios.billing.service.InvoiceAvailabilityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Controlador administrativo para gestión de reglas de facturación corporativa,
 * calendario mensual y centro de incidencias y alertas.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminBillingRulesAndIncidentsController {

    private final ClientBillingRuleService clientBillingRuleService;
    private final BillingIncidentService incidentService;
    private final InvoiceAvailabilityService availabilityService;

    public AdminBillingRulesAndIncidentsController(
            ClientBillingRuleService clientBillingRuleService,
            BillingIncidentService incidentService,
            InvoiceAvailabilityService availabilityService
    ) {
        this.clientBillingRuleService = clientBillingRuleService;
        this.incidentService = incidentService;
        this.availabilityService = availabilityService;
    }

    // -------------------------------------------------------------
    // 1. Clientes y Reglas de Facturación
    // -------------------------------------------------------------

    public record ClientBillingRuleDto(
            UUID id,
            String nit,
            @JsonProperty("razonSocial") String businessName,
            @JsonProperty("tipoFacturacion") String billingType,
            @JsonProperty("reglaFacturacion") ClientBillingRule billingRule,
            @JsonProperty("diaCorteCorporativo") Integer corporateCutoffDay,
            @JsonProperty("diaCorteEfectivo") int effectiveCutoffDay,
            @JsonProperty("detalleReglaCorporativa") String corporateRuleDetails,
            @JsonProperty("configuracionCompleta") boolean configurationComplete,
            @JsonProperty("activo") boolean active
    ) {}

    public record UpdateClientBillingRuleRequest(
            @NotNull(message = "La regla de facturación es requerida")
            @JsonProperty("reglaFacturacion") ClientBillingRule billingRule,
            @Min(value = 1, message = "El día de corte debe ser mayor o igual a 1")
            @Max(value = 28, message = "El día de corte debe ser menor o igual a 28")
            @JsonProperty("diaCorteCorporativo") Integer corporateCutoffDay,
            @JsonProperty("detalleReglaCorporativa") String corporateRuleDetails
    ) {}

    @GetMapping("/reglas-facturacion/clientes")
    @PreAuthorize("hasAnyAuthority('BILLING_RULES_VIEW', 'CLIENTS_VIEW', 'SUPER_ADMIN')")
    public List<ClientBillingRuleDto> listClientRules() {
        return clientBillingRuleService.listClients().stream().map(this::toRuleDto).toList();
    }

    @PutMapping("/reglas-facturacion/clientes/{id}")
    @PreAuthorize("hasAnyAuthority('BILLING_RULES_MANAGE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientBillingRuleDto> updateClientBillingRule(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateClientBillingRuleRequest request
    ) {
        CorporateClient saved = clientBillingRuleService.updateRule(
                id, request.billingRule(), request.corporateCutoffDay(), request.corporateRuleDetails());
        return ResponseEntity.ok(toRuleDto(saved));
    }

    private ClientBillingRuleDto toRuleDto(CorporateClient c) {
        int effectiveDay = c.getBillingRule() == ClientBillingRule.ORDINARIA
                ? 2
                : (c.getCorporateCutoffDay() != null ? c.getCorporateCutoffDay() : 0);
        return new ClientBillingRuleDto(
                c.getId(),
                c.getNit(),
                c.getBusinessName(),
                c.getBillingType() != null ? c.getBillingType().name() : null,
                c.getBillingRule(),
                c.getCorporateCutoffDay(),
                effectiveDay,
                c.getCorporateRuleDetails(),
                c.isConfigurationComplete(),
                c.getStatus() == RecordStatus.ACTIVO
        );
    }

    // -------------------------------------------------------------
    // 2. Centro de Incidencias y Alertas
    // -------------------------------------------------------------

    public record IncidentDto(
            UUID id,
            UUID clienteId,
            String nit,
            @JsonProperty("razonSocial") String businessName,
            String periodo,
            BillingIncidentType tipoIncidencia,
            BillingIncidentStatus estadoAtencion,
            String descripcion,
            Integer diaCorteEsperado,
            LocalDate fechaCorteEsperada,
            java.time.Instant fechaDeteccion,
            java.time.Instant fechaResolucion,
            String observacionesResolucion,
            String responsableAsignado,
            String observacionesSeguimiento,
            boolean notificacionEnviada,
            String canalNotificacion
    ) {
        public static IncidentDto fromDomain(BillingIncident inc) {
            return new IncidentDto(
                    inc.getId(),
                    inc.getClientId(),
                    inc.getNit(),
                    inc.getBusinessName(),
                    inc.getPeriod(),
                    inc.getIncidentType(),
                    inc.getStatus(),
                    inc.getDescription(),
                    inc.getExpectedCutoffDay(),
                    inc.getExpectedCutoffDate(),
                    inc.getDetectedAt(),
                    inc.getResolvedAt(),
                    inc.getResolutionObservations(),
                    inc.getAssignedTo(),
                    inc.getFollowUpNotes(),
                    inc.isNotificationSent(),
                    inc.getNotificationChannel()
            );
        }
    }

    public record ResolveIncidentRequest(
            @NotNull(message = "Las observaciones de resolución son obligatorias")
            String observaciones,
            String resueltoPor
    ) {}

    public record AssignIncidentRequest(
            @NotNull(message = "El responsable es obligatorio")
            String responsable,
            String observaciones
    ) {}

    @GetMapping("/incidencias-facturacion")
    @PreAuthorize("hasAnyAuthority('BILLING_INCIDENTS_VIEW', 'INVOICES_VIEW', 'SUPER_ADMIN')")
    public List<IncidentDto> listIncidents(
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) BillingIncidentStatus estado
    ) {
        return incidentService.listIncidents(periodo, estado).stream()
                .map(IncidentDto::fromDomain)
                .toList();
    }

    @PostMapping("/incidencias-facturacion/detectar")
    @PreAuthorize("hasAnyAuthority('BILLING_INCIDENTS_MANAGE', 'SUPER_ADMIN')")
    public List<IncidentDto> triggerDetection(
            @RequestParam String periodo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaEvaluacion
    ) {
        YearMonth ym = YearMonth.parse(periodo.trim());
        LocalDate evalDate = fechaEvaluacion != null ? fechaEvaluacion : LocalDate.now(InvoiceAvailabilityService.BUSINESS_ZONE);
        return incidentService.detectAndRegisterIncidents(ym, evalDate).stream()
                .map(IncidentDto::fromDomain)
                .toList();
    }

    @PutMapping("/incidencias-facturacion/{id}/resolver")
    @PreAuthorize("hasAnyAuthority('BILLING_INCIDENTS_MANAGE', 'SUPER_ADMIN')")
    public ResponseEntity<IncidentDto> resolveIncident(
            @PathVariable UUID id,
            @Valid @RequestBody ResolveIncidentRequest request
    ) {
        BillingIncident resolved = incidentService.resolveIncident(id, request.observaciones(), request.resueltoPor());
        return ResponseEntity.ok(IncidentDto.fromDomain(resolved));
    }

    @PutMapping("/incidencias-facturacion/{id}/asignar")
    @PreAuthorize("hasAnyAuthority('BILLING_INCIDENTS_MANAGE', 'SUPER_ADMIN')")
    public ResponseEntity<IncidentDto> assignIncident(
            @PathVariable UUID id,
            @Valid @RequestBody AssignIncidentRequest request
    ) {
        BillingIncident assigned = incidentService.assignIncident(id, request.responsable(), request.observaciones());
        return ResponseEntity.ok(IncidentDto.fromDomain(assigned));
    }

    // -------------------------------------------------------------
    // 3. Calendario Mensual de Facturación
    // -------------------------------------------------------------

    public record BillingCalendarSummaryDto(
            String periodo,
            LocalDate fechaEvaluacion,
            int totalClientesActivos,
            int disponiblesATiempo,
            int disponiblesConRetraso,
            int pendientesDentroPlazo,
            int incidenciasFacturaFaltante,
            int configuracionesIncompletas,
            List<InvoiceAvailabilityService.ClientPeriodAvailabilityReport> clientes
    ) {}

    @GetMapping("/calendario-facturacion")
    @PreAuthorize("hasAnyAuthority('BILLING_RULES_VIEW', 'INVOICES_VIEW', 'SUPER_ADMIN')")
    public BillingCalendarSummaryDto getBillingCalendar(
            @RequestParam String periodo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaEvaluacion
    ) {
        YearMonth ym = YearMonth.parse(periodo.trim());
        LocalDate evalDate = fechaEvaluacion != null ? fechaEvaluacion : LocalDate.now(InvoiceAvailabilityService.BUSINESS_ZONE);

        List<InvoiceAvailabilityService.ClientPeriodAvailabilityReport> reports =
                availabilityService.evaluateAllActiveClients(ym, evalDate);

        int aTiempo = 0, retraso = 0, pendiente = 0, faltante = 0, incompleta = 0;
        for (var r : reports) {
            switch (r.status()) {
                case DISPONIBLE_A_TIEMPO -> aTiempo++;
                case DISPONIBLE_CON_RETRASO -> retraso++;
                case PENDIENTE_DENTRO_DEL_PLAZO, REGLA_CORPORATIVA_ESPECIAL -> pendiente++;
                case INCIDENCIA_FACTURA_FALTANTE -> faltante++;
                case CONFIGURACION_INCOMPLETA -> incompleta++;
            }
        }

        return new BillingCalendarSummaryDto(
                ym.toString(),
                evalDate,
                reports.size(),
                aTiempo,
                retraso,
                pendiente,
                faltante,
                incompleta,
                reports
        );
    }
}
