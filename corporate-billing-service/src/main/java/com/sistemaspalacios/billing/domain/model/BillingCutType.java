package com.sistemaspalacios.billing.domain.model;

public enum BillingCutType {
    ADVANCE("ADELANTADO"),
    ARREARS("VENCIDO");

    private final String spanishName;

    BillingCutType(String spanishName) {
        this.spanishName = spanishName;
    }

    public String getSpanishName() {
        return spanishName;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static BillingCutType fromSpanish(String text) {
        if (text == null) return null;
        for (BillingCutType b : values()) {
            if (b.name().equalsIgnoreCase(text) || b.spanishName.equalsIgnoreCase(text)) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unknown BillingCutType: " + text);
    }
}
