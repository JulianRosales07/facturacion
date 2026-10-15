package com.sistemaspalacios.billing.domain.model;

/**
 * Cómo eligió el cliente aplicar las retenciones de un pago (HU-51). Lo decide siempre el
 * cliente; el sistema nunca lo infiere, porque las bases mínimas dan resultados distintos:
 * dos facturas de 100.000 no alcanzan individualmente una base de 150.000 y juntas sí.
 */
public enum RetentionApplicationMode {
    /** Cada factura lleva sus propios conceptos; bases mínimas y ReteIVA se evalúan por factura. */
    PER_INVOICE,
    /** Un solo conjunto de conceptos sobre la suma de base gravable e IVA de las facturas. */
    CONSOLIDATED
}
