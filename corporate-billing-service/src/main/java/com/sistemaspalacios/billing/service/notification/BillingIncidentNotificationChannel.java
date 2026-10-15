package com.sistemaspalacios.billing.service.notification;

import com.sistemaspalacios.billing.domain.model.BillingIncident;

/**
 * Contrato extensible para el despacho de alertas de incidencias de facturación
 * (soporta correo electrónico, webhook corporativo u otros canales configurados).
 */
public interface BillingIncidentNotificationChannel {

    /**
     * Identificador del canal (ej. "EMAIL", "WEBHOOK", "REGISTRO_OPERATIVO").
     */
    String getChannelName();

    /**
     * Indica si el canal cuenta con configuración activa (destinatarios o URL configurada).
     */
    boolean isConfigured();

    /**
     * Despacha la notificación de la incidencia.
     * Si no está configurado, la incidencia se preserva en base de datos para consulta operativa.
     */
    void sendNotification(BillingIncident incident);
}
