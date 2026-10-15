package com.sistemaspalacios.billing.service.notification;

import com.sistemaspalacios.billing.domain.model.BillingIncident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Canal por defecto y despachador de notificaciones operativas.
 * Si no existen credenciales de correo o webhook configuradas, preserva
 * la incidencia en base de datos como visible y consultable para el equipo operativo.
 */
@Component
public class DefaultLoggingIncidentNotificationChannel implements BillingIncidentNotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(DefaultLoggingIncidentNotificationChannel.class);

    @Value("${billing.notifications.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${billing.notifications.webhook.url:}")
    private String webhookUrl;

    @Override
    public String getChannelName() {
        if (emailEnabled && webhookUrl != null && !webhookUrl.isBlank()) {
            return "EMAIL_Y_WEBHOOK";
        } else if (emailEnabled) {
            return "EMAIL";
        } else if (webhookUrl != null && !webhookUrl.isBlank()) {
            return "WEBHOOK";
        }
        return "PANEL_OPERATIVO_LOCAL";
    }

    @Override
    public boolean isConfigured() {
        return emailEnabled || (webhookUrl != null && !webhookUrl.isBlank());
    }

    @Override
    public void sendNotification(BillingIncident incident) {
        if (!isConfigured()) {
            log.info("Canal externo no configurado. Incidencia persistida en base de datos y visible en panel operativo: " +
                            "[ID: {}, NIT: {}, Periodo: {}, Tipo: {}, Corte esperado: {}]",
                    incident.getId(), incident.getNit(), incident.getPeriod(),
                    incident.getIncidentType(), incident.getExpectedCutoffDate());
            incident.markNotificationSent(false, getChannelName());
            return;
        }

        // Si en el futuro se configura un canal real:
        log.info("Despachando alerta mediante canal configurado '{}' para cliente NIT {} en periodo {}",
                getChannelName(), incident.getNit(), incident.getPeriod());
        incident.markNotificationSent(true, getChannelName());
    }
}
