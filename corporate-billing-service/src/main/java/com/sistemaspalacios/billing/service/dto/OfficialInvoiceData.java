package com.sistemaspalacios.billing.service.dto;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.vo.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OfficialInvoiceData(String nit, String businessName, BillingType billingType,
        String invoiceNumber, LocalDate issueDate, LocalDate dueDate, String cufe,
        Money total, List<Item> items) {
    public record Item(String code, String description, BigDecimal quantity, Money subtotal, Money iva) {}
}
