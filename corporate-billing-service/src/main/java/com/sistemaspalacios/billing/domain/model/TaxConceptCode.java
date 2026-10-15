package com.sistemaspalacios.billing.domain.model;

/**
 * Colombian tax and withholding concept codes.
 * Official regulatory terms retained per convention.
 */
public enum TaxConceptCode {
    RETEFUENTE(1, "Retención en la fuente", false),
    RETEICA(2, "Retención de ICA", true),
    RETEIVA(3, "Retención de IVA", false),
    ESTAMPILLA(4, "Estampillas", true);

    private final int id;
    private final String description;
    private final boolean territorial;

    TaxConceptCode(int id, String description, boolean territorial) {
        this.id = id;
        this.description = description;
        this.territorial = territorial;
    }

    public int getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerritorial() {
        return territorial;
    }
}
