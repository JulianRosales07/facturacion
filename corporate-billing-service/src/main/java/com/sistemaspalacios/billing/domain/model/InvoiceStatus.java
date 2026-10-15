package com.sistemaspalacios.billing.domain.model;

public enum InvoiceStatus {
    DRAFT("BORRADOR"),
    CONSOLIDATED("CONSOLIDADA"),
    PAID("PAGADA"),
    /**
     * La factura fue sustituida por una versión posterior durante una regeneración
     * (Sprint 10). Se conserva íntegra como histórico y deja de ser vigente, por lo
     * que no participa del índice único de unicidad activa ni de la cartera.
     */
    SUPERSEDED("REEMPLAZADA"),
    VOIDED("ANULADA");

    private final String dbValue;

    InvoiceStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    /**
     * Una factura es vigente cuando todavía representa la obligación real del cliente
     * para su periodo. Refleja exactamente el predicado del índice parcial
     * {@code uq_factura_cliente_periodo_activa}.
     */
    public boolean isCurrent() {
        return this != VOIDED && this != SUPERSEDED;
    }
}
