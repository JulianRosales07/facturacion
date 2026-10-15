package com.sistemaspalacios.billing.domain.model;

/**
 * Clasificación de la incidencia del ciclo mensual de facturación.
 */
public enum BillingIncidentType {
    /**
     * La factura oficial Siigo no ha sido importada tras vencer la fecha límite de corte.
     */
    FACTURA_FALTANTE,

    /**
     * El cliente corporativo especial no cuenta con un día de corte contractual válido (1 a 28).
     */
    CONFIGURACION_INCOMPLETA
}
