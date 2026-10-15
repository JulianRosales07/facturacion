package com.sistemaspalacios.billing.domain.model;

/** Desde dónde se diligenció una retención. */
public enum SourceChannel {
    /** El cliente en corporate-payment-gateway-web. */
    PAYMENT_GATEWAY,
    /** Un analista en corporate-billing-service-web. */
    ADMIN_WEB,
    /** Registro manual de un recaudo fuera de la interfaz administrativa. */
    MANUAL_PAYMENT,
    /** Integración directa con el API (sin canal declarado). */
    API
}
