package com.sistemaspalacios.billing.service.dto;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.vo.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OfficialInvoiceData(String nit, String businessName, BillingType billingType,
        String invoiceNumber, LocalDate issueDate, LocalDate dueDate, String cufe,
        Money total, List<Item> items, Money outstandingBalance, java.time.Instant balanceCutoffAt) {
    public OfficialInvoiceData(String nit, String businessName, BillingType billingType,
            String invoiceNumber, LocalDate issueDate, LocalDate dueDate, String cufe,
            Money total, List<Item> items) {
        this(nit, businessName, billingType, invoiceNumber, issueDate, dueDate, cufe, total, items, null, null);
    }
    public record Item(String code, String description, BigDecimal quantity, Money subtotal, Money iva) {}
}
