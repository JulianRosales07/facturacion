package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.ServiceClassification;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.TaxProfile;
import com.sistemaspalacios.billing.domain.vo.WithholdingDetail;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure domain service for calculating taxes and withholdings (HU-14, HU-15).
 * Critical invariant: Withholdings are for payment net settlement only.
 * They do NOT modify the official invoice total (subtotal + IVA) nor the accounting entry.
 */
public class TaxCalculator {

    public static final BigDecimal DEFAULT_IVA_RATE = BigDecimal.valueOf(19);

    /**
     * Calculates IVA value for a given taxable base.
     */
    public Money calculateIva(Money taxableBase, boolean taxed, BigDecimal ivaRate) {
        if (!taxed || taxableBase == null || taxableBase.isZero()) {
            return Money.ZERO;
        }
        BigDecimal rate = ivaRate != null ? ivaRate : DEFAULT_IVA_RATE;
        return taxableBase.percentage(rate);
    }

    /**
     * Calculates withholdings at payment settlement time based on selected concepts (Sprint 9, HU-51, HU-52).
     *
     * @param subtotal Taxable base of the invoice(s)
     * @param iva Total IVA of the invoice(s)
     * @param selectedConcepts The tax concept validities explicitly selected by the client for this payment
     * @return Full calculation breakdown including rounding difference and per-concept applicability
     */
    public com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult calculatePaymentWithholdings(
            Money subtotal,
            Money iva,
            List<TaxConceptValidity> selectedConcepts
    ) {
        Money safeSubtotal = subtotal != null ? subtotal : Money.ZERO;
        Money safeIva = iva != null ? iva : Money.ZERO;
        return calculatePaymentWithholdings(safeSubtotal.plus(safeIva), safeSubtotal, safeIva, selectedConcepts);
    }

    /**
     * Igual que {@link #calculatePaymentWithholdings(Money, Money, List)} pero con el total de las
     * facturas tomado de sus valores oficiales persistidos, en lugar de reconstruirlo como base + IVA
     * (con ítems exentos, excluidos o ajustes ambos pueden no coincidir). Liquida cada concepto a su
     * tarifa de catálogo (su máximo); los pagos usan {@link #settleWithholdings}.
     */
    public com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult calculatePaymentWithholdings(
            Money totalInvoices,
            Money subtotal,
            Money iva,
            List<TaxConceptValidity> selectedConcepts
    ) {
        return settleWithholdings(totalInvoices, subtotal, iva, selectedConcepts == null ? List.of()
                : selectedConcepts.stream().map(com.sistemaspalacios.billing.domain.vo.SelectedWithholding::atCatalogMaximum).toList());
    }

    /**
     * Liquidación de un pago con los conceptos y porcentajes ingresados por el cliente (HU-51/52).
     * El catálogo aporta la base mínima y el tipo (ReteIVA sobre el IVA; el resto sobre la base
     * gravable); el porcentaje es el del cliente, ya validado contra el máximo del concepto.
     */
    public com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult settleWithholdings(
            Money totalInvoices,
            Money subtotal,
            Money iva,
            List<com.sistemaspalacios.billing.domain.vo.SelectedWithholding> selectedConcepts
    ) {
        Money safeSubtotal = subtotal != null ? subtotal : Money.ZERO;
        Money safeIva = iva != null ? iva : Money.ZERO;
        Money grossAmount = totalInvoices != null ? totalInvoices : safeSubtotal.plus(safeIva);

        if (selectedConcepts == null || selectedConcepts.isEmpty()) {
            Money rounded = grossAmount.roundToPeso();
            Money diff = rounded.minus(grossAmount);
            return new com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult(
                    grossAmount,
                    safeSubtotal,
                    safeIva,
                    Money.ZERO,
                    grossAmount,
                    rounded,
                    diff,
                    List.of()
            );
        }

        List<com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult.WithholdingItemEvaluation> evaluations = new ArrayList<>();
        Money totalWithholdings = Money.ZERO;

        for (com.sistemaspalacios.billing.domain.vo.SelectedWithholding selection : selectedConcepts) {
            TaxConceptValidity validity = selection.concept();
            TaxConceptCode concept = validity.conceptCode();
            String conceptName = validity.getName();
            int version = validity.getVersionNumber();
            BigDecimal rate = selection.percentage();
            Money minBase = validity.minimumBase();

            // ReteIVA applies strictly over IVA; ReteFuente, ReteICA and Estampillas over subtotal (HU-52)
            Money base = (concept == TaxConceptCode.RETEIVA) ? safeIva : safeSubtotal;

            if (base.compareTo(minBase) < 0) {
                evaluations.add(new com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult.WithholdingItemEvaluation(
                        validity.getId(),
                        version,
                        concept,
                        conceptName,
                        rate,
                        minBase,
                        base,
                        Money.ZERO,
                        false,
                        "No alcanza la base mínima de " + formatCop(minBase)
                ));
            } else {
                Money withheld = base.percentage(rate);
                totalWithholdings = totalWithholdings.plus(withheld);
                evaluations.add(new com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult.WithholdingItemEvaluation(
                        validity.getId(),
                        version,
                        concept,
                        conceptName,
                        rate,
                        minBase,
                        base,
                        withheld,
                        true,
                        null
                ));
            }
        }

        Money rawNet = grossAmount.minus(totalWithholdings);
        if (rawNet.isNegative()) {
            rawNet = Money.ZERO;
        }

        Money roundedNet = rawNet.roundToPeso();
        Money roundingDiff = roundedNet.minus(rawNet);

        return new com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult(
                grossAmount,
                safeSubtotal,
                safeIva,
                totalWithholdings,
                rawNet,
                roundedNet,
                roundingDiff,
                evaluations
        );
    }

    /**
     * Totaliza un pago liquidado en modo PER_INVOICE: cada factura se evaluó por separado con
     * {@link #calculatePaymentWithholdings(Money, Money, Money, List)} (bases mínimas y ReteIVA
     * sobre su propia base/IVA) y aquí solo se suman. El redondeo al peso se aplica una vez sobre
     * el neto total que se transfiere, no factura por factura.
     */
    public com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult combinePerInvoice(
            List<com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult> perInvoice
    ) {
        Money gross = Money.ZERO;
        Money base = Money.ZERO;
        Money iva = Money.ZERO;
        Money withholdings = Money.ZERO;
        List<com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult.WithholdingItemEvaluation> evaluations = new ArrayList<>();
        for (var result : perInvoice) {
            gross = gross.plus(result.grossAmount());
            base = base.plus(result.totalTaxableBase());
            iva = iva.plus(result.totalIva());
            withholdings = withholdings.plus(result.totalWithholdings());
            evaluations.addAll(result.evaluations());
        }
        Money rawNet = gross.minus(withholdings);
        Money roundedNet = rawNet.roundToPeso();
        return new com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult(
                gross, base, iva, withholdings, rawNet, roundedNet, roundedNet.minus(rawNet), evaluations);
    }

    /** $150.000 / $1.234,56 — formato colombiano para los motivos que ve el cliente. */
    static String formatCop(Money value) {
        java.text.DecimalFormatSymbols symbols = new java.text.DecimalFormatSymbols();
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        BigDecimal amount = value.amount();
        String pattern = amount.stripTrailingZeros().scale() <= 0 ? "#,##0" : "#,##0.00";
        return "$" + new java.text.DecimalFormat(pattern, symbols).format(amount);
    }

    /**
     * Calculates withholdings for a corporate client based on invoice subtotal, IVA, and active legal validities.
     *
     * @param client Corporate client with tax profile and municipality
     * @param subtotal Taxable base of the invoice
     * @param iva Total IVA of the invoice
     * @param cutoffDate Date of the billing cut to resolve concept validities
     * @param availableValidities Active concept validities (vigencia_concepto)
     * @return List of calculated withholding details
     */
    public List<WithholdingDetail> calculateWithholdings(
            CorporateClient client,
            Money subtotal,
            Money iva,
            LocalDate cutoffDate,
            List<TaxConceptValidity> availableValidities
    ) {
        Objects.requireNonNull(client, "client cannot be null");
        Objects.requireNonNull(subtotal, "subtotal cannot be null");
        Objects.requireNonNull(iva, "iva cannot be null");
        Objects.requireNonNull(cutoffDate, "cutoffDate cannot be null");

        TaxProfile taxProfile = client.getTaxProfile();
        if (taxProfile == null || !taxProfile.appliesWithholding()) {
            return List.of();
        }

        if (availableValidities == null || availableValidities.isEmpty()) {
            return List.of();
        }

        List<WithholdingDetail> result = new ArrayList<>();

        for (TaxProfile.TaxProfileItem profileItem : taxProfile.items()) {
            TaxConceptCode concept = profileItem.conceptCode();
            ServiceClassification classification = profileItem.classification();

            // Find applicable validity matching concept, date, classification, and municipality
            Optional<TaxConceptValidity> applicableValidityOpt = availableValidities.stream()
                    .filter(v -> v.conceptCode() == concept)
                    .filter(v -> v.isApplicable(cutoffDate, classification, client.getMunicipality()))
                    .findFirst();

            if (applicableValidityOpt.isEmpty()) {
                continue;
            }

            TaxConceptValidity validity = applicableValidityOpt.get();

            // Base determination: ReteIVA is applied on IVA, other concepts on subtotal
            Money base = (concept == TaxConceptCode.RETEIVA) ? iva : subtotal;

            // Check minimum base threshold
            if (base.compareTo(validity.minimumBase()) < 0) {
                continue; // Threshold not met, no withholding applied
            }

            // Determine rate: agreed override takes precedence over legal validity rate
            BigDecimal rate = profileItem.percentageOverride() != null
                    ? profileItem.percentageOverride()
                    : validity.getMaxPercentage();

            Money withheldAmount = base.percentage(rate);

            if (withheldAmount.isPositive()) {
                result.add(new WithholdingDetail(
                        concept,
                        concept.getDescription(),
                        base,
                        rate,
                        withheldAmount
                ));
            }
        }

        return result;
    }
}
