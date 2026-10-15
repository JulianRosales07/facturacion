package com.sistemaspalacios.billing.domain.model;

public enum AccountingNature {
    D("DEBITO"),
    C("CREDITO");

    private final String description;

    AccountingNature(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
