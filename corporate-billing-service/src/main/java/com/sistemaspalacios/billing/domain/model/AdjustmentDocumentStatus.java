package com.sistemaspalacios.billing.domain.model;

/**
 * Estados del ciclo de vida de un documento de ajuste (PostgreSQL estado_documento_ajuste).
 */
public enum AdjustmentDocumentStatus {
    DRAFT("BORRADOR"),
    APPLIED("APLICADO"),
    VOIDED("ANULADO");

    private final String dbValue;

    AdjustmentDocumentStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }
}
