package com.sistemaspalacios.billing.domain.model;

/**
 * Regla de facturación aplicable a un cliente corporativo.
 */
public enum ClientBillingRule {
    /**
     * Regla ordinaria mensual:
     * La factura oficial del periodo en curso debe estar disponible para consulta y pago
     * el día 1 o 2 de ese mismo mes, sujeta a la disponibilidad real del documento en Siigo.
     */
    ORDINARIA,

    /**
     * Regla corporativa especial:
     * Comportamiento definido por la condición contractual, ciclo de corte acordado o
     * términos específicos de ese cliente corporativo.
     */
    ESPECIAL_CORPORATIVA
}
