package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.*;
import com.sistemaspalacios.billing.domain.service.NoveltyAdjustmentCalculator;
import com.sistemaspalacios.billing.domain.service.ProrationCalculator;
import com.sistemaspalacios.billing.domain.vo.DateRange;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.*;
import com.sistemaspalacios.billing.service.dto.ClientNoveltiesSummaryDto;
import com.sistemaspalacios.billing.service.dto.ClientNoveltyDetailDto;
import com.sistemaspalacios.billing.service.dto.InvoiceRefreshResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Consulta y reproceso de las novedades de un cliente en un periodo.
 * <p>
 * Muestra cada novedad registrada con su estado frente a la facturación (pendiente,
 * aplicada, ajuste pendiente o aplicado) y su impacto económico real, más los ajustes de
 * periodos anteriores que se cobran en este. No crea novedades: las que existen llegan por
 * la importación de órdenes o la sincronización.
 */
@Service
public class ClientNoveltyService {

    private static final Logger log = LoggerFactory.getLogger(ClientNoveltyService.class);

    private final RepositorioCliente repositorioCliente;
    private final RepositorioSede repositorioSede;
    private final RepositorioPeriodo repositorioPeriodo;
    private final RepositorioNovedad repositorioNovedad;
    private final RepositorioCuotaInstalacion repositorioCuotaInstalacion;
    private final BillingService billingService;
    private final RepositorioFactura repositorioFactura;
    private final NoveltyAdjustmentCalculator adjustmentCalculator = new NoveltyAdjustmentCalculator();

    public ClientNoveltyService(
            RepositorioCliente repositorioCliente,
            RepositorioSede repositorioSede,
            RepositorioPeriodo repositorioPeriodo,
            RepositorioNovedad repositorioNovedad,
            RepositorioCuotaInstalacion repositorioCuotaInstalacion,
            BillingService billingService,
            RepositorioFactura repositorioFactura
    ) {
        this.repositorioCliente = repositorioCliente;
        this.repositorioSede = repositorioSede;
        this.repositorioPeriodo = repositorioPeriodo;
        this.repositorioNovedad = repositorioNovedad;
        this.repositorioCuotaInstalacion = repositorioCuotaInstalacion;
        this.billingService = billingService;
        this.repositorioFactura = repositorioFactura;
    }

    /** Resumen de novedades del cliente en el periodo, con su estado e impacto. */
    @Transactional(readOnly = true)
    public ClientNoveltiesSummaryDto getClientNoveltiesSummary(String nit, String periodCode) {
        String effectivePeriod = resolvePeriodCode(periodCode);
        CorporateClient client = repositorioCliente.findByNit(nit).orElse(null);
        BillingPeriod period = repositorioPeriodo.findByCode(effectivePeriod).orElse(null);
        if (client == null || period == null) {
            return emptySummary(effectivePeriod);
        }
        return calculateSummaryForClient(client, period);
    }

    /**
     * Reprocesa las novedades del cliente en el periodo (reintento seguro): las incorpora a su
     * factura en preparación o, si ya está emitida, las deja como ajuste para la siguiente.
     * Es idempotente: repetirlo no duplica conceptos ni importes.
     */
    @Transactional
    public ClientNoveltiesSummaryDto consumeAndSyncClientNovelties(String nit, String periodCode) {
        String effectivePeriod = resolvePeriodCode(periodCode);
        CorporateClient client = repositorioCliente.findByNit(nit)
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado: " + nit));
        BillingPeriod period = repositorioPeriodo.findByCode(effectivePeriod).orElse(null);
        if (period == null) {
            return emptySummary(effectivePeriod);
        }
        InvoiceRefreshResult result = billingService.refreshEditableInvoice(client.getId(), period.getId());
        log.info("Reproceso de novedades del cliente {} en {}: {}", nit, effectivePeriod, result.toMessage());
        return calculateSummaryForClient(client, period);
    }

    private ClientNoveltiesSummaryDto calculateSummaryForClient(CorporateClient client, BillingPeriod period) {
        List<SubscriptionSite> sites = repositorioSede.findByClientId(client.getId());
        Map<UUID, SubscriptionSite> sitesById = new HashMap<>();
        sites.forEach(s -> sitesById.put(s.getId(), s));

        BigDecimal canonBaseTotal = sites.stream()
                .filter(s -> s.isActive() && s.getMonthlyValue() != null)
                .map(s -> s.getMonthlyValue().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Novedades del periodo y ajustes de periodos anteriores que se cobran en este.
        List<OrderUpdate> updates = new ArrayList<>(repositorioNovedad.findByPeriodId(period.getId()).stream()
                .filter(u -> sitesById.containsKey(u.getSiteId()))
                .toList());
        Map<UUID, BillingPeriod> periods = new HashMap<>();
        periods.put(period.getId(), period);
        if (!sitesById.isEmpty()) {
            repositorioNovedad.findBySiteIdInAndProcessingStatusIn(sitesById.keySet(),
                            List.of(NoveltyProcessingStatus.PENDIENTE_AJUSTE, NoveltyProcessingStatus.AJUSTE_APLICADO)).stream()
                    .filter(u -> !u.getPeriodId().equals(period.getId()))
                    .filter(u -> {
                        BillingPeriod p = periods.computeIfAbsent(u.getPeriodId(), id -> repositorioPeriodo.findById(id).orElse(null));
                        return p != null && p.getStartDate().isBefore(period.getStartDate());
                    })
                    .forEach(updates::add);
        }

        List<ClientNoveltyDetailDto> noveltyDtos = new ArrayList<>();
        BigDecimal sumAjustes = BigDecimal.ZERO;
        Map<UUID, String> invoiceNumbers = new HashMap<>();
        updates.sort(Comparator.comparing(OrderUpdate::getEffectiveDate, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (OrderUpdate u : updates) {
            BillingPeriod p = periods.get(u.getPeriodId());
            SubscriptionSite site = sitesById.get(u.getSiteId());
            DateRange range = new DateRange(p.getStartDate(), p.getEndDate());
            LocalDate effective = u.getEffectiveDate() != null ? u.getEffectiveDate() : p.getStartDate();
            LocalDate from = effective.isBefore(p.getStartDate()) ? p.getStartDate() : effective;
            int days = from.isAfter(p.getEndDate()) ? 0 : ProrationCalculator.commercialDays(from, p.getEndDate());

            Money impact = u.isAdjustment() ? u.getImpactAmount() : adjustmentCalculator.impactOf(u, range);
            BigDecimal signed = impact != null ? impact.amount() : BigDecimal.ZERO;
            sumAjustes = sumAjustes.add(signed);

            boolean carried = !u.getPeriodId().equals(period.getId());
            String observation = (u.getObservation() != null ? u.getObservation() : "Novedad de órdenes")
                    + (carried ? " · ajuste del periodo " + p.getCode() : "");
            String invoiceNumber = u.getInvoiceId() == null ? null
                    : invoiceNumbers.computeIfAbsent(u.getInvoiceId(),
                            id -> repositorioFactura.findById(id).map(Invoice::getInvoiceNumber).orElse(null));

            noveltyDtos.add(new ClientNoveltyDetailDto(
                    u.getId().toString(),
                    u.getUpdateType().name(),
                    carried ? "AJUSTE_PERIODO_ANTERIOR" : null,
                    site != null ? site.getSiteName() : "Sede",
                    site != null ? site.getExternalOrderCode() : null,
                    effective.toString(),
                    days,
                    u.getPreviousValue() != null ? u.getPreviousValue().amount()
                            : (site != null && site.getMonthlyValue() != null ? site.getMonthlyValue().amount() : BigDecimal.ZERO),
                    signed.abs(),
                    impact == null ? "INFORMATIVO" : (signed.signum() < 0 ? "RESTA" : "SUMA"),
                    observation,
                    frontendStatus(u),
                    u.getValue() != null ? u.getValue().amount() : null,
                    u.getProcessingStatus().name(),
                    u.getProcessingDetail(),
                    p.getCode(),
                    u.getInvoiceId() != null ? u.getInvoiceId().toString() : null,
                    invoiceNumber
            ));
        }

        BigDecimal sumCuotas = BigDecimal.ZERO;
        for (SubscriptionSite site : sites) {
            List<InstallationFee> fees = new ArrayList<>(repositorioCuotaInstalacion.findBySiteIdAndStatus(site.getId(), InstallationFeeStatus.PENDING));
            fees.addAll(repositorioCuotaInstalacion.findBySiteIdAndStatus(site.getId(), InstallationFeeStatus.BILLING));
            for (InstallationFee fee : fees) {
                if (fee.getInstallmentAmount() == null) continue;
                BigDecimal installment = fee.getInstallmentAmount().amount();
                sumCuotas = sumCuotas.add(installment);
                noveltyDtos.add(new ClientNoveltyDetailDto(
                        "fee-" + fee.getId(), "CUOTA_INSTALACION", "ADICION", site.getSiteName(), site.getExternalOrderCode(),
                        period.getStartDate().toString(), null,
                        fee.getTotalAmount() != null ? fee.getTotalAmount().amount() : installment, installment, "SUMA",
                        String.format("Cuota de instalación %d de %d", fee.getBilledInstallments() + 1, fee.getTotalInstallments()),
                        fee.getStatus() == InstallationFeeStatus.BILLING ? "CONSUMIDA" : "PENDIENTE_FACTURAR"));
            }
        }

        return new ClientNoveltiesSummaryDto(period.getCode(), noveltyDtos.size(), canonBaseTotal, sumAjustes, sumCuotas,
                canonBaseTotal.add(sumAjustes).add(sumCuotas), noveltyDtos);
    }

    /** Estados que conoce la pantalla: PENDIENTE_FACTURAR, CONSUMIDA y EN_REVISION. */
    private static String frontendStatus(OrderUpdate u) {
        return switch (u.getProcessingStatus()) {
            case APLICADA, AJUSTE_APLICADO -> "CONSUMIDA";
            case PENDIENTE -> "PENDIENTE_FACTURAR";
            case PENDIENTE_AJUSTE -> u.getImpactAmount() == null ? "EN_REVISION" : "PENDIENTE_FACTURAR";
        };
    }

    private String resolvePeriodCode(String periodCode) {
        if (periodCode != null && periodCode.matches("^\\d{4}-\\d{2}$")) {
            return periodCode.trim();
        }
        return YearMonth.now().toString();
    }

    private ClientNoveltiesSummaryDto emptySummary(String periodCode) {
        return new ClientNoveltiesSummaryDto(periodCode, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, List.of());
    }
}
