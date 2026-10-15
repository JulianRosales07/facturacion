package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingIncident;
import com.sistemaspalacios.billing.domain.model.BillingIncidentStatus;
import com.sistemaspalacios.billing.domain.model.BillingIncidentType;
import com.sistemaspalacios.billing.domain.model.ClientBillingRule;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.RecordStatus;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioIncidenciaFacturacion;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.notification.BillingIncidentNotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Servicio de detección, registro persistente, trazabilidad y resolución de incidencias
 * del ciclo mensual de facturación (facturas faltantes y configuraciones incompletas).
 */
@Service
public class BillingIncidentService {

    private static final Logger log = LoggerFactory.getLogger(BillingIncidentService.class);

    private final RepositorioIncidenciaFacturacion repositorioIncidencia;
    private final RepositorioCliente repositorioCliente;
    private final RepositorioFactura repositorioFactura;
    private final RepositorioPeriodo repositorioPeriodo;
    private final BillingIncidentNotificationChannel notificationChannel;

    public BillingIncidentService(
            RepositorioIncidenciaFacturacion repositorioIncidencia,
            RepositorioCliente repositorioCliente,
            RepositorioFactura repositorioFactura,
            RepositorioPeriodo repositorioPeriodo,
            BillingIncidentNotificationChannel notificationChannel
    ) {
        this.repositorioIncidencia = Objects.requireNonNull(repositorioIncidencia);
        this.repositorioCliente = Objects.requireNonNull(repositorioCliente);
        this.repositorioFactura = Objects.requireNonNull(repositorioFactura);
        this.repositorioPeriodo = Objects.requireNonNull(repositorioPeriodo);
        this.notificationChannel = Objects.requireNonNull(notificationChannel);
    }

    /**
     * Evalúa y registra de manera persistente las incidencias de facturación para todos los clientes activos.
     * Garantiza idempotencia: no crea duplicados para el mismo cliente, periodo y tipo de incidencia.
     */
    @Transactional
    public List<BillingIncident> detectAndRegisterIncidents(YearMonth period, LocalDate evaluationDate) {
        Objects.requireNonNull(period, "period cannot be null");
        LocalDate evalDate = evaluationDate != null ? evaluationDate : LocalDate.now(InvoiceAvailabilityService.BUSINESS_ZONE);
        String periodStr = period.toString();

        List<CorporateClient> activeClients = repositorioCliente.findByStatus(RecordStatus.ACTIVO);
        List<BillingIncident> detectedIncidents = new ArrayList<>();

        for (CorporateClient client : activeClients) {
            if (client.getBillingRule() == ClientBillingRule.ESPECIAL_CORPORATIVA) {
                Integer cutoffDay = client.getCorporateCutoffDay();
                if (cutoffDay == null || cutoffDay < 1 || cutoffDay > 28) {
                    // Configuración incompleta: incidencia administrativa diferenciada
                    String desc = "El cliente corporativo especial no tiene configurado un día de corte contractual válido (1 a 28). " +
                            "Detalle contractual actual: " + (client.getCorporateRuleDetails() != null ? client.getCorporateRuleDetails() : "Sin detalle");
                    BillingIncident inc = getOrCreateIncident(
                            client, periodStr, BillingIncidentType.CONFIGURACION_INCOMPLETA, desc, null, null
                    );
                    detectedIncidents.add(inc);
                    continue;
                }

                // Cliente especial con corte estructurado
                LocalDate cutoffDate = period.atDay(cutoffDay);
                boolean invoicePresent = isInvoicePresent(client, periodStr);
                if (!invoicePresent && evalDate.isAfter(cutoffDate)) {
                    String desc = "Factura oficial del periodo " + periodStr + " no ha sido importada tras vencer la fecha contractual (día " +
                            cutoffDay + ", corte " + cutoffDate + ", evaluado al " + evalDate + ").";
                    BillingIncident inc = getOrCreateIncident(
                            client, periodStr, BillingIncidentType.FACTURA_FALTANTE, desc, cutoffDay, cutoffDate
                    );
                    detectedIncidents.add(inc);
                }
            } else {
                // Cliente Ordinario: corte día 2
                LocalDate ordinaryCutoff = period.atDay(2);
                boolean invoicePresent = isInvoicePresent(client, periodStr);
                if (!invoicePresent && evalDate.isAfter(ordinaryCutoff)) {
                    String desc = "Factura oficial del periodo " + periodStr + " no ha sido importada desde Siigo al finalizar el día 2 " +
                            "(corte ordinario " + ordinaryCutoff + ", evaluado al " + evalDate + ").";
                    BillingIncident inc = getOrCreateIncident(
                            client, periodStr, BillingIncidentType.FACTURA_FALTANTE, desc, 2, ordinaryCutoff
                    );
                    detectedIncidents.add(inc);
                }
            }
        }

        return detectedIncidents;
    }

    private BillingIncident getOrCreateIncident(
            CorporateClient client,
            String period,
            BillingIncidentType type,
            String description,
            Integer expectedCutoffDay,
            LocalDate expectedCutoffDate
    ) {
        Optional<BillingIncident> existingOpt = repositorioIncidencia
                .findByClientIdAndPeriodAndIncidentType(client.getId(), period, type);

        if (existingOpt.isPresent()) {
            BillingIncident existing = existingOpt.get();
            log.debug("Incidencia ya existente para cliente NIT {} en periodo {} (tipo {}). Preservando registro único.",
                    client.getNit(), period, type);
            return existing;
        }

        BillingIncident incident = new BillingIncident(
                UUID.randomUUID(),
                client.getId(),
                client.getNit(),
                client.getBusinessName(),
                period,
                type,
                description,
                expectedCutoffDay,
                expectedCutoffDate,
                Instant.now()
        );

        BillingIncident saved = repositorioIncidencia.saveAndFlush(incident);

        // Notificación controlada y no repetitiva
        try {
            notificationChannel.sendNotification(saved);
            saved = repositorioIncidencia.saveAndFlush(saved);
        } catch (Exception e) {
            log.error("Error al despachar notificación de incidencia id {}: {}", saved.getId(), e.getMessage());
        }

        log.warn("Nueva incidencia persistida en BD: NIT {}, Periodo {}, Tipo {}, Corte {}",
                client.getNit(), period, type, expectedCutoffDate);
        return saved;
    }

    /**
     * Resuelve automáticamente incidencias de tipo FACTURA_FALTANTE cuando la factura oficial
     * es finalmente importada y validada en el sistema.
     */
    @Transactional
    public void autoResolveMissingInvoiceIncident(UUID clientId, String period, String invoiceNumber) {
        if (clientId == null || period == null) return;

        List<BillingIncident> incidents = repositorioIncidencia.findByClientIdAndPeriod(clientId, period);
        for (BillingIncident inc : incidents) {
            if (inc.getIncidentType() == BillingIncidentType.FACTURA_FALTANTE
                    && inc.getStatus() != BillingIncidentStatus.RESUELTA) {
                inc.resolve(
                        "Resuelto automáticamente: Factura oficial " + invoiceNumber + " fue importada satisfactoriamente y consolidada.",
                        "SISTEMA_IMPORTACION_SIIGO"
                );
                repositorioIncidencia.saveAndFlush(inc);
                log.info("Incidencia FACTURA_FALTANTE resuelta automáticamente para cliente NIT {} en periodo {}",
                        inc.getNit(), period);
            }
        }
    }

    /**
     * Resolución manual por parte de un operador o administrador.
     */
    @Transactional
    public BillingIncident resolveIncident(UUID incidentId, String observations, String resolvedBy) {
        BillingIncident incident = repositorioIncidencia.findById(incidentId)
                .orElseThrow(() -> new NoSuchElementException("Incidencia no encontrada con ID: " + incidentId));
        incident.resolve(observations, resolvedBy);
        return repositorioIncidencia.saveAndFlush(incident);
    }

    /**
     * Asignación de responsable y seguimiento.
     */
    @Transactional
    public BillingIncident assignIncident(UUID incidentId, String assignedTo, String notes) {
        BillingIncident incident = repositorioIncidencia.findById(incidentId)
                .orElseThrow(() -> new NoSuchElementException("Incidencia no encontrada con ID: " + incidentId));
        incident.assign(assignedTo, notes);
        return repositorioIncidencia.saveAndFlush(incident);
    }

    @Transactional(readOnly = true)
    public List<BillingIncident> listIncidents(String period, BillingIncidentStatus status) {
        if (period != null && !period.isBlank() && status != null) {
            return repositorioIncidencia.findByPeriodAndStatus(period, status);
        } else if (period != null && !period.isBlank()) {
            return repositorioIncidencia.findByPeriod(period);
        } else if (status != null) {
            return repositorioIncidencia.findByStatus(status);
        }
        return repositorioIncidencia.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<BillingIncident> getIncidentById(UUID id) {
        return repositorioIncidencia.findById(id);
    }

    private boolean isInvoicePresent(CorporateClient client, String targetPeriodCode) {
        List<Invoice> invoices = repositorioFactura.findByClientId(client.getId());
        return invoices.stream()
                .filter(Invoice::isCurrentVersion)
                .filter(inv -> inv.getStatus() != InvoiceStatus.VOIDED && inv.getStatus() != InvoiceStatus.SUPERSEDED)
                .anyMatch(inv -> {
                    String pCode = periodCodeOf(inv);
                    return targetPeriodCode.equals(pCode);
                });
    }

    private String periodCodeOf(Invoice invoice) {
        if (invoice.getPeriodId() != null) {
            var p = repositorioPeriodo.findById(invoice.getPeriodId());
            if (p.isPresent()) return p.get().getCode();
        }
        return invoice.getIssueDate() != null ? YearMonth.from(invoice.getIssueDate()).toString() : null;
    }
}
