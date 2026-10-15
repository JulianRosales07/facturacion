package com.sistemaspalacios.billing.domain.model;

/**
 * State of imported line within a file (estado_linea_importada).
 */
public enum ImportedLineStatus {
    PENDIENTE("PENDIENTE"),
    PROCESADA("PROCESADA"),
    ERROR("ERROR");

    private final String dbValue;

    ImportedLineStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }
}
