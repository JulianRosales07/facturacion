package com.sistemaspalacios.billing.domain.model;

/**
 * Batch status matching PostgreSQL 'estado_lote' enum.
 */
public enum ExportBatchStatus {
    GENERADO,
    ENVIADO,
    CONFIRMADO,
    ERROR
}
