package com.sistemaspalacios.billing.domain.vo;

import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Concepto del catálogo elegido por el cliente con el porcentaje que él mismo ingresó (HU-51).
 * El catálogo aporta tipo, base mínima, vigencia y el máximo permitido; la tarifa es la del cliente.
 */
public record SelectedWithholding(TaxConceptValidity concept, BigDecimal percentage) {

    /** Hasta 4 decimales: tarifas como ReteICA 9,66 por mil (0,966%). */
    public static final int MAX_PERCENTAGE_SCALE = 4;

    public SelectedWithholding {
        Objects.requireNonNull(concept, "concept cannot be null");
        if (percentage == null) {
            throw new IllegalArgumentException("Ingrese el porcentaje de " + concept.getName());
        }
        if (percentage.signum() <= 0) {
            throw new IllegalArgumentException("El porcentaje de " + concept.getName() + " debe ser mayor que 0");
        }
        if (percentage.stripTrailingZeros().scale() > MAX_PERCENTAGE_SCALE) {
            throw new IllegalArgumentException("El porcentaje de " + concept.getName() + " admite máximo "
                    + MAX_PERCENTAGE_SCALE + " decimales");
        }
        if (percentage.compareTo(concept.getMaxPercentage()) > 0) {
            throw new IllegalArgumentException("El porcentaje de " + concept.getName() + " no puede superar el máximo permitido de "
                    + concept.getMaxPercentage().stripTrailingZeros().toPlainString() + "%");
        }
    }

    /**
     * Varias estampillas pueden coexistir; ReteFuente, ReteICA y ReteIVA se practican una sola vez
     * sobre la misma base, o se retendría dos veces el mismo impuesto.
     */
    public static void requireOnePerType(java.util.List<SelectedWithholding> selections) {
        java.util.Set<com.sistemaspalacios.billing.domain.model.TaxConceptCode> seen =
                java.util.EnumSet.noneOf(com.sistemaspalacios.billing.domain.model.TaxConceptCode.class);
        for (SelectedWithholding selection : selections) {
            var code = selection.concept().getConceptCode();
            if (code != com.sistemaspalacios.billing.domain.model.TaxConceptCode.ESTAMPILLA && !seen.add(code)) {
                throw new IllegalArgumentException("Solo puede seleccionar un concepto de " + code);
            }
        }
    }

    /** Tarifa del catálogo (su máximo). Solo para cálculos que no provienen de un pagador. */
    public static SelectedWithholding atCatalogMaximum(TaxConceptValidity concept) {
        return new SelectedWithholding(concept, concept.getMaxPercentage());
    }
}
