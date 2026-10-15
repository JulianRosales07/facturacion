package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.vo.DateRange;
import com.sistemaspalacios.billing.domain.vo.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Novedades que llegan cuando la factura de su periodo ya fue emitida o pagada.
 * <p>
 * Ese documento no se modifica. Se calcula lo que la novedad habría cambiado en su periodo
 * (su <b>impacto</b>, antes de IVA) y se lleva a la siguiente factura en preparación del
 * cliente: un impacto positivo es una línea de ajuste; uno negativo (a favor del cliente)
 * se compensa como descuento por valor, porque la factura electrónica no admite líneas
 * negativas.
 * <p>
 * Supuesto: el documento emitido cobró el periodo a la tarifa anterior a la novedad
 * (facturación por mes adelantado). Por eso el impacto se mide contra {@code valor_anterior}.
 */
public class NoveltyAdjustmentCalculator {

    private final ProrationCalculator prorationCalculator = new ProrationCalculator();

    /**
     * Impacto antes de IVA de la novedad sobre su periodo, desde la fecha efectiva hasta el
     * cierre. {@code null} cuando no se puede calcular con certeza (tarifa anterior
     * desconocida o renuncia al prorrateo): requiere revisión manual y no se aplica solo.
     */
    public Money impactOf(OrderUpdate update, DateRange period) {
        if (update.waivesProration()) {
            return null;
        }
        LocalDate effective = update.getEffectiveDate();
        if (effective == null || effective.isAfter(period.endDate())) {
            return Money.ZERO;
        }
        LocalDate from = effective.isBefore(period.startDate()) ? period.startDate() : effective;
        Money previous = update.getPreviousValue();
        Money value = update.getValue();
        return switch (update.getUpdateType()) {
            case TRASLADO_DIRECCION -> Money.ZERO;
            case ALTA, REACTIVACION -> value != null && value.isPositive() ? prorated(value, from, period) : null;
            case CAMBIO_PLAN, CAMBIO_SERVICIO -> {
                if (previous == null || value == null || !value.isPositive()) yield null;
                Money delta = value.minus(previous);
                Money amount = prorated(delta.isNegative() ? Money.ZERO.minus(delta) : delta, from, period);
                yield delta.isNegative() ? Money.ZERO.minus(amount) : amount;
            }
            case BAJA, SUSPENSION -> previous == null ? null : Money.ZERO.minus(prorated(previous, from, period));
        };
    }

    private Money prorated(Money monthly, LocalDate from, DateRange period) {
        if (monthly.isZero()) return Money.ZERO;
        return prorationCalculator.calculateSegment(monthly, from, period.endDate(), period).proratedAmount();
    }

    /**
     * Ajuste a incorporar en una factura.
     *
     * @param amount      impacto antes de IVA; positivo cobra, negativo compensa
     * @param description descripción para la línea (y para Siigo)
     */
    public record Adjustment(UUID updateId, UUID siteId, String description, LocalDate from, LocalDate to, Money amount) {}

    /**
     * Incorpora los ajustes a la factura: los positivos como líneas propias; los negativos
     * como descuento por valor repartido sobre las líneas existentes. Devuelve lo que no se
     * pudo compensar porque la factura no alcanzaba (saldo a favor del cliente).
     */
    public Money apply(Invoice invoice, List<Adjustment> adjustments) {
        Money credit = Money.ZERO;
        List<InvoiceItem> added = new ArrayList<>();
        for (Adjustment a : adjustments) {
            if (a.amount() == null || a.amount().isZero()) continue;
            if (a.amount().isNegative()) {
                credit = credit.plus(Money.ZERO.minus(a.amount()));
                continue;
            }
            InvoiceItem item = new InvoiceItem(null, invoice.getId(), BillableItemType.CORPORATE_SERVICE, a.siteId(),
                    a.description(), BigDecimal.ONE, a.amount(), BigDecimal.ZERO, true, TaxCalculator.DEFAULT_IVA_RATE);
            item.setSourceUpdateId(a.updateId());
            item.setCoveredPeriod(a.from(), a.to());
            added.add(item);
        }
        invoice.addItems(added);

        Money remaining = credit;
        for (InvoiceItem item : invoice.getItems()) {
            if (!remaining.isPositive()) break;
            Money room = item.getTaxableBase();
            Money applied = remaining.isGreaterThan(room) ? room : remaining;
            if (applied.isPositive()) {
                item.addFixedDiscount(applied);
                remaining = remaining.minus(applied);
            }
        }
        invoice.recalculateTotals();
        return remaining;
    }
}
