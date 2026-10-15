package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import com.sistemaspalacios.billing.service.dto.NicBillingReportResponse;
import com.sistemaspalacios.billing.service.dto.NicReportItemDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    private final RepositorioFactura repositorioFactura;
    private final RepositorioPeriodo repositorioPeriodo;
    private final RepositorioSede repositorioSede;

    public ReportServiceImpl(
            RepositorioFactura repositorioFactura,
            RepositorioPeriodo repositorioPeriodo,
            RepositorioSede repositorioSede
    ) {
        this.repositorioFactura = repositorioFactura;
        this.repositorioPeriodo = repositorioPeriodo;
        this.repositorioSede = repositorioSede;
    }

    @Override
    public NicBillingReportResponse generateNicBillingReport(String periodo) {
        if (periodo == null || periodo.isBlank()) {
            return new NicBillingReportResponse(periodo, 0, BigDecimal.ZERO, List.of());
        }

        UUID periodId = resolvePeriodId(periodo);
        if (periodId == null) {
            return new NicBillingReportResponse(periodo, 0, BigDecimal.ZERO, List.of());
        }

        List<Invoice> invoices = repositorioFactura.findByPeriodId(periodId).stream()
                .filter(inv -> inv.getStatus() != InvoiceStatus.VOIDED)
                .toList();

        Map<UUID, Optional<SubscriptionSite>> siteCache = new HashMap<>();
        Map<String, NicAccumulator> accumulators = new HashMap<>();

        for (Invoice invoice : invoices) {
            if (invoice.getItems() == null) continue;
            for (InvoiceItem item : invoice.getItems()) {
                String nic = "SIN_NIC";
                String siteName = "Sin sede";
                if (item.getSiteId() != null) {
                    Optional<SubscriptionSite> siteOpt = siteCache.computeIfAbsent(
                            item.getSiteId(),
                            repositorioSede::findById
                    );
                    if (siteOpt.isPresent()) {
                        SubscriptionSite site = siteOpt.get();
                        if (site.getNic() != null && !site.getNic().isBlank()) {
                            nic = site.getNic();
                        }
                        if (site.getSiteName() != null) {
                            siteName = site.getSiteName();
                        }
                    }
                }

                BigDecimal subtotal = item.getTaxableBase() != null ? item.getTaxableBase().getAmount() : BigDecimal.ZERO;
                BigDecimal iva = item.getIvaValue() != null ? item.getIvaValue().getAmount() : BigDecimal.ZERO;
                BigDecimal total = item.getTotalValue() != null ? item.getTotalValue().getAmount() : BigDecimal.ZERO;

                NicAccumulator acc = accumulators.get(nic);
                if (acc == null) {
                    acc = new NicAccumulator(nic, siteName, invoice.getClientNit(), invoice.getClientBusinessName());
                    accumulators.put(nic, acc);
                }
                acc.add(subtotal, iva, total);
            }
        }

        List<NicReportItemDto> reportItems = new ArrayList<>();
        BigDecimal granTotal = BigDecimal.ZERO;

        for (NicAccumulator acc : accumulators.values()) {
            reportItems.add(new NicReportItemDto(
                    acc.nic,
                    acc.siteName,
                    acc.clientNit,
                    acc.clientBusinessName,
                    acc.itemCount,
                    acc.totalSubtotal,
                    acc.totalIva,
                    acc.totalFacturado
            ));
            granTotal = granTotal.add(acc.totalFacturado);
        }

        reportItems.sort(Comparator.comparing(NicReportItemDto::nic));

        return new NicBillingReportResponse(
                periodo,
                reportItems.size(),
                granTotal,
                reportItems
        );
    }

    private UUID resolvePeriodId(String periodo) {
        Optional<BillingPeriod> byCode = repositorioPeriodo.findByCode(periodo);
        if (byCode.isPresent()) {
            return byCode.get().getId();
        }
        try {
            UUID id = UUID.fromString(periodo);
            Optional<BillingPeriod> byId = repositorioPeriodo.findById(id);
            if (byId.isPresent()) {
                return byId.get().getId();
            }
        } catch (IllegalArgumentException ignored) {
            // Not a UUID
        }
        return null;
    }

    private static class NicAccumulator {
        final String nic;
        final String siteName;
        final String clientNit;
        final String clientBusinessName;
        long itemCount = 0;
        BigDecimal totalSubtotal = BigDecimal.ZERO;
        BigDecimal totalIva = BigDecimal.ZERO;
        BigDecimal totalFacturado = BigDecimal.ZERO;

        NicAccumulator(String nic, String siteName, String clientNit, String clientBusinessName) {
            this.nic = nic;
            this.siteName = siteName;
            this.clientNit = clientNit;
            this.clientBusinessName = clientBusinessName;
        }

        void add(BigDecimal subtotal, BigDecimal iva, BigDecimal total) {
            this.itemCount++;
            this.totalSubtotal = this.totalSubtotal.add(subtotal);
            this.totalIva = this.totalIva.add(iva);
            this.totalFacturado = this.totalFacturado.add(total);
        }
    }
}
