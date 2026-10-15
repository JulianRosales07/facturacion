package com.sistemaspalacios.billing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Tarea programada que ejecuta automáticamente la detección y auditoría de disponibilidad
 * de facturas al corte para clientes ordinarios y corporativos especiales.
 */
@Component
@ConditionalOnProperty(name = "billing.availability.scheduler.enabled", havingValue = "true", matchIfMissing = false)
public class ScheduledBillingAvailabilityJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledBillingAvailabilityJob.class);

    private final BillingIncidentService incidentService;

    @Autowired
    public ScheduledBillingAvailabilityJob(BillingIncidentService incidentService) {
        this.incidentService = incidentService;
    }

    /**
     * Se ejecuta diariamente a las 06:00 AM (hora Colombia) o según la expresión configurada.
     */
    @Scheduled(cron = "${billing.availability.check.cron:0 0 6 * * *}")
    public void executeDailyAvailabilityCheck() {
        YearMonth currentPeriod = YearMonth.now(InvoiceAvailabilityService.BUSINESS_ZONE);
        LocalDate today = LocalDate.now(InvoiceAvailabilityService.BUSINESS_ZONE);

        log.info("Iniciando auditoría automática programada de disponibilidad de facturas para el periodo {} al día {}",
                currentPeriod, today);

        try {
            var incidents = incidentService.detectAndRegisterIncidents(currentPeriod, today);
            log.info("Auditoría programada finalizada: {} incidencias evaluadas/registradas.", incidents.size());
        } catch (Exception e) {
            log.error("Error durante la auditoría automática programada de facturas: {}", e.getMessage(), e);
        }
    }
}
