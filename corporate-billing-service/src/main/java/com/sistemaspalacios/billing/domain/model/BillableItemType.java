package com.sistemaspalacios.billing.domain.model;

public enum BillableItemType {
    CORPORATE_SERVICE("SERVICIO_CORPORATIVO"),
    SVA_STREAMING("SVA_STREAMING"),
    SVA_PRODUCT("SVA_PRODUCTO");

    private final String dbValue;

    BillableItemType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }
}
