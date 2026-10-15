package com.sistemaspalacios.billing.domain.model;

/**
 * Tipos de documentos de ajuste contable (PostgreSQL tipo_documento_ajuste).
 */
public enum AdjustmentDocumentType {
    CREDIT_NOTE("NOTA_CREDITO"),
    DEBIT_NOTE("NOTA_DEBITO");

    private final String dbValue;

    AdjustmentDocumentType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }
}
