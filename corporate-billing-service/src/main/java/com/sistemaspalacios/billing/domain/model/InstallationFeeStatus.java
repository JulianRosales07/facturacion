package com.sistemaspalacios.billing.domain.model;

public enum InstallationFeeStatus {
    PENDING("PENDIENTE"),
    BILLING("FACTURANDO"),
    PAID("PAGADA"),
    VOIDED("ANULADA");

    private final String dbValue;

    InstallationFeeStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }
}
