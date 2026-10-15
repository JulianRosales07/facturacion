package com.sistemaspalacios.billing.domain.model;

/**
 * State of imported order file (estado_archivo).
 */
public enum FileStatus {
    RECIBIDO("RECIBIDO"),
    PROCESANDO("PROCESANDO"),
    PROCESADO("PROCESADO"),
    ERROR("ERROR");

    private final String dbValue;

    FileStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }
}
