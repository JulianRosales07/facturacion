package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.InstallationFee;
import com.sistemaspalacios.billing.domain.model.InstallationFeeStatus;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.TaxProfile;
import com.sistemaspalacios.billing.service.ordenes.OrderSystemClient;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncRecordDto;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncResultDto;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderUpdateDto;
import com.sistemaspalacios.billing.service.ordenes.dto.SubscriptionOrderDto;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioCuotaInstalacion;
import com.sistemaspalacios.billing.repository.RepositorioNovedad;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of OrderService and ServicioOrdenes.
 * Manages idempotent synchronization and master data updates from the Order System.
 */
@Service
public class OrderServiceImpl implements ServicioOrdenes {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final OrderSystemClient orderSystemClient;
    private final RepositorioCliente repositorioCliente;
    private final RepositorioSede repositorioSede;
    private final RepositorioPeriodo repositorioPeriodo;
    private final RepositorioNovedad repositorioNovedad;
    private final RepositorioCuotaInstalacion repositorioCuotaInstalacion;
    private final com.sistemaspalacios.billing.service.metrics.BillingMetricsPort billingMetrics;

    /** Incorpora las novedades sincronizadas a la factura en preparación. Inyección opcional. */
    private BillingService billingService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBillingService(BillingService billingService) {
        this.billingService = billingService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrderServiceImpl(
            OrderSystemClient orderSystemClient,
            RepositorioCliente repositorioCliente,
            RepositorioSede repositorioSede,
            RepositorioPeriodo repositorioPeriodo,
            RepositorioNovedad repositorioNovedad,
            RepositorioCuotaInstalacion repositorioCuotaInstalacion,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.sistemaspalacios.billing.service.metrics.BillingMetricsPort billingMetrics
    ) {
        this.orderSystemClient = orderSystemClient;
        this.repositorioCliente = repositorioCliente;
        this.repositorioSede = repositorioSede;
        this.repositorioPeriodo = repositorioPeriodo;
        this.repositorioNovedad = repositorioNovedad;
        this.repositorioCuotaInstalacion = repositorioCuotaInstalacion;
        this.billingMetrics = billingMetrics;
    }

    public OrderServiceImpl(
            OrderSystemClient orderSystemClient,
            RepositorioCliente repositorioCliente,
            RepositorioSede repositorioSede,
            RepositorioPeriodo repositorioPeriodo,
            RepositorioNovedad repositorioNovedad,
            RepositorioCuotaInstalacion repositorioCuotaInstalacion
    ) {
        this(orderSystemClient, repositorioCliente, repositorioSede, repositorioPeriodo, repositorioNovedad, repositorioCuotaInstalacion, null);
    }

    @Override
    @Transactional
    public OrderSyncResultDto syncPeriod(String periodCode) {
        log.info("Starting synchronization with Order System for billing period '{}'", periodCode);
        List<SubscriptionOrderDto> subscriptions = orderSystemClient.fetchSubscriptions(periodCode);
        List<OrderUpdateDto> updates = orderSystemClient.fetchUpdates(periodCode);
        return syncPeriod(periodCode, subscriptions, updates);
    }

    @Override
    @Transactional
    public OrderSyncResultDto syncPeriod(String periodCode, List<SubscriptionOrderDto> subscriptions, List<OrderUpdateDto> updates) {
        Objects.requireNonNull(periodCode, "periodCode cannot be null");
        BillingPeriod period = findOrCreatePeriod(periodCode);
        OrderSyncResultDto result = new OrderSyncResultDto(periodCode);

        log.info("Processing {} subscriptions and {} updates for period '{}'",
                subscriptions.size(), updates.size(), periodCode);

        // Tarifa de cada sede antes de que el paso 1 la actualice: las novedades del paso 2 la
        // guardan como valor anterior.
        java.util.Map<String, Money> ratesBeforeSync = new java.util.HashMap<>();
        for (SubscriptionOrderDto subDto : subscriptions) {
            repositorioSede.findByExternalOrderCodeAndOrigin(subDto.externalOrderCode(), subDto.origin())
                    .ifPresent(s -> ratesBeforeSync.putIfAbsent(subDto.origin() + ":" + subDto.externalOrderCode(), s.getMonthlyValue()));
        }

        // 1. Process subscriptions (clients + sites + installation fees)
        for (SubscriptionOrderDto subDto : subscriptions) {
            try {
                boolean isNewClient = !repositorioCliente.existsByNit(subDto.nit());
                CorporateClient client = upsertClient(subDto);
                result.incrementClientsProcessed();
                if (isNewClient) result.incrementClientsCreated();

                boolean isNewSite = repositorioSede.findByExternalOrderCodeAndOrigin(
                        subDto.externalOrderCode(), subDto.origin()).isEmpty();
                SubscriptionSite site = upsertSite(subDto, client.getId());
                result.incrementSitesProcessed();
                if (isNewSite) result.incrementSitesCreated();

            } catch (Exception e) {
                log.error("Error processing subscription {}: {}", subDto.externalOrderCode(), e.getMessage());
                result.incrementErrorCount();
                result.addMessage("Subscription error " + subDto.externalOrderCode() + ": " + e.getMessage());
            }
        }

        // 2. Process updates / novelties
        Set<UUID> clientsWithNovelties = new LinkedHashSet<>();
        for (OrderUpdateDto updateDto : updates) {
            try {
                OrderUpdate update = recordUpdate(updateDto, period.getId(),
                        ratesBeforeSync.get(updateDto.origin() + ":" + updateDto.externalOrderCode()));
                if (update != null) {
                    result.incrementUpdatesProcessed();
                    repositorioSede.findById(update.getSiteId())
                            .ifPresent(site -> clientsWithNovelties.add(site.getClientId()));
                }
            } catch (Exception e) {
                log.error("Error processing order update for code {}: {}", updateDto.externalOrderCode(), e.getMessage());
                result.incrementErrorCount();
                result.addMessage("Update error " + updateDto.externalOrderCode() + ": " + e.getMessage());
            }
        }

        // 3. Las novedades se incorporan a la factura en preparación de cada cliente.
        if (billingService != null) {
            for (UUID clientId : clientsWithNovelties) {
                result.addMessage(billingService.refreshEditableInvoice(clientId, period.getId()).toMessage());
            }
        }

        log.info("Finished synchronization for period '{}': {}", periodCode, result);
        if (billingMetrics != null) {
            billingMetrics.recordOrdersSynchronized(periodCode, result.getSitesProcessed());
        }
        return result;
    }

    @Override
    @Transactional
    public CorporateClient upsertClient(SubscriptionOrderDto dto) {
        Objects.requireNonNull(dto.nit(), "Client NIT cannot be null");
        Optional<CorporateClient> existingOpt = repositorioCliente.findByNit(dto.nit());

        if (existingOpt.isPresent()) {
            CorporateClient client = existingOpt.get();
            // Un dato vacío en el archivo no borra el que ya tiene el cliente.
            if (dto.businessName() != null && !dto.businessName().isBlank()) client.setBusinessName(dto.businessName());
            if (dto.address() != null) client.setTaxAddress(dto.address());
            if (dto.billingEmail() != null) client.setBillingEmail(dto.billingEmail());
            if (dto.phone() != null) client.setPhone(dto.phone());
            if (dto.brandId() != null) client.setBrandId(dto.brandId());
            if (dto.zoneId() != null) client.setZoneId(dto.zoneId());
            if (dto.billingType() != null) client.setBillingType(dto.billingType());
            if (dto.municipality() != null) client.setMunicipality(dto.municipality());
            if (dto.appliesWithholding() != null) client.setAppliesWithholding(dto.appliesWithholding());
            client.activate();
            return repositorioCliente.save(client);
        } else {
            CorporateClient newClient = new CorporateClient(
                    null,
                    dto.nit(),
                    dto.businessName() != null && !dto.businessName().isBlank() ? dto.businessName() : "Cliente NIT " + dto.nit(),
                    dto.address(),
                    dto.billingEmail(),
                    dto.phone(),
                    dto.brandId() != null ? dto.brandId() : (short) 1,
                    dto.zoneId() != null ? dto.zoneId() : (short) 1,
                    dto.billingType(),
                    dto.appliesWithholding() != null ? dto.appliesWithholding() : false,
                    dto.municipality(),
                    TaxProfile.withoutWithholding(dto.municipality())
            );
            return repositorioCliente.save(newClient);
        }
    }

    @Override
    @Transactional
    public SubscriptionSite upsertSite(SubscriptionOrderDto dto, UUID clientId) {
        Objects.requireNonNull(dto.externalOrderCode(), "externalOrderCode cannot be null");
        Objects.requireNonNull(dto.origin(), "origin cannot be null");

        Optional<SubscriptionSite> existingOpt = repositorioSede.findByExternalOrderCodeAndOrigin(
                dto.externalOrderCode(), dto.origin());

        SubscriptionSite site;
        if (existingOpt.isPresent()) {
            site = existingOpt.get();
            site.setMonthlyValue(Money.of(dto.monthlyValue()));
            if (dto.certificationDate() != null) site.setCertificationDate(dto.certificationDate());
            if (dto.address() != null) site.setAddress(dto.address());
            if (dto.siteName() != null) site.setSiteName(dto.siteName());
            if (dto.nic() != null) site.setNic(dto.nic());
            site.activate();
            site = repositorioSede.save(site);
        } else {
            site = new SubscriptionSite(
                    null,
                    clientId,
                    dto.externalOrderCode(),
                    dto.origin(),
                    dto.nic(),
                    dto.siteName(),
                    dto.address(),
                    Money.of(dto.monthlyValue()),
                    dto.certificationDate(),
                    true
            );
            site = repositorioSede.save(site);
        }

        // Handle Installation Fee if specified
        if (dto.installationTotalAmount() != null && dto.installationTotalInstallments() != null && dto.installationTotalInstallments() > 0) {
            Optional<InstallationFee> activeFee = repositorioCuotaInstalacion.findFirstBySiteIdAndStatusIn(
                    site.getId(), List.of(InstallationFeeStatus.PENDING, InstallationFeeStatus.BILLING));

            if (activeFee.isEmpty()) {
                Money totalAmount = Money.of(dto.installationTotalAmount());
                Money installmentAmount = dto.installationInstallmentAmount() != null
                        ? Money.of(dto.installationInstallmentAmount())
                        : totalAmount.divide(dto.installationTotalInstallments());

                InstallationFee fee = new InstallationFee(
                        null,
                        site.getId(),
                        totalAmount,
                        dto.installationTotalInstallments(),
                        0,
                        installmentAmount,
                        InstallationFeeStatus.PENDING
                );
                repositorioCuotaInstalacion.save(fee);
                log.info("Registered new installation fee for site {} with {} installments", site.getId(), dto.installationTotalInstallments());
            }
        }

        return site;
    }

    @Override
    @Transactional
    public OrderUpdate recordUpdate(OrderUpdateDto dto, UUID periodId) {
        return recordUpdate(dto, periodId, null);
    }

    /**
     * @param rateBeforeChange tarifa de la sede antes de que este mismo lote la actualizara;
     *                         null = la que tiene ahora (nadie la ha cambiado todavía)
     */
    @Transactional
    public OrderUpdate recordUpdate(OrderUpdateDto dto, UUID periodId, Money rateBeforeChange) {
        Objects.requireNonNull(dto.externalOrderCode(), "externalOrderCode cannot be null");
        Objects.requireNonNull(dto.updateType(), "updateType cannot be null");

        Optional<SubscriptionSite> siteOpt = repositorioSede.findByExternalOrderCodeAndOrigin(
                dto.externalOrderCode(), dto.origin());

        if (siteOpt.isEmpty()) {
            log.warn("Cannot record update: site not found for external code '{}' and origin '{}'",
                    dto.externalOrderCode(), dto.origin());
            return null;
        }

        SubscriptionSite site = siteOpt.get();
        List<OrderUpdate> existingUpdates = repositorioNovedad.findBySiteIdAndPeriodId(site.getId(), periodId);

        boolean include = dto.includeInConsolidated() != null ? dto.includeInConsolidated() : true;

        // RN-05: la novedad se identifica por sede, periodo, tipo y fecha efectiva (índice
        // uq_novedad_orden_clave). Si llega otra vez, se actualiza conservando su id; dos
        // cambios de plan en fechas distintas del mismo mes son dos novedades.
        Optional<OrderUpdate> matchingUpdate = existingUpdates.stream()
                .filter(u -> u.getUpdateType() == dto.updateType())
                .filter(u -> Objects.equals(u.getEffectiveDate(), dto.effectiveDate()))
                .findFirst();

        Money previousRate = previousRateFor(dto, rateBeforeChange != null ? rateBeforeChange : site.getMonthlyValue());

        if (matchingUpdate.isPresent()) {
            OrderUpdate existing = matchingUpdate.get();
            existing.refreshDetails(Money.of(dto.value()), include, dto.observation());
            existing.recordPreviousValue(previousRate); // solo si aún no la tenía
            return repositorioNovedad.save(existing);
        }

        OrderUpdate newUpdate = new OrderUpdate(
                null,
                site.getId(),
                dto.updateType(),
                periodId,
                Money.of(dto.value()),
                dto.effectiveDate(),
                include,
                dto.observation()
        );
        newUpdate.recordPreviousValue(previousRate);
        return repositorioNovedad.save(newUpdate);
    }

    /**
     * Tarifa anterior que vale la pena guardar: solo en cambios de plan o de servicio y solo
     * si difiere del valor nuevo. Si son iguales, la sede ya traía el valor nuevo (un reenvío
     * del mismo archivo) y no dice nada de la tarifa que regía antes.
     */
    private static Money previousRateFor(OrderUpdateDto dto, Money siteRate) {
        if (siteRate == null || !siteRate.isPositive()) {
            return null;
        }
        // Baja o suspensión: la tarifa con que se venía cobrando, para calcular los días a devolver
        // si el periodo ya estaba facturado.
        if (dto.updateType() == OrderUpdateType.BAJA || dto.updateType() == OrderUpdateType.SUSPENSION) {
            return siteRate;
        }
        boolean changesRate = dto.updateType() == OrderUpdateType.CAMBIO_PLAN
                || dto.updateType() == OrderUpdateType.CAMBIO_SERVICIO;
        if (!changesRate) {
            return null;
        }
        if (dto.value() != null && Money.of(dto.value()).equals(siteRate)) {
            return null;
        }
        return siteRate;
    }

    @Override
    @Transactional
    public SubscriptionSite processRecord(OrderSyncRecordDto record, String periodCode) {
        BillingPeriod period = findOrCreatePeriod(periodCode);
        SubscriptionOrderDto subDto = record.toSubscriptionOrderDto();
        CorporateClient client = upsertClient(subDto);
        // La tarifa se toma antes de que la fila actualice la sede al valor nuevo.
        Money rateBeforeChange = repositorioSede.findByExternalOrderCodeAndOrigin(subDto.externalOrderCode(), subDto.origin())
                .map(SubscriptionSite::getMonthlyValue).orElse(null);
        SubscriptionSite site = upsertSite(subDto, client.getId());

        OrderUpdateDto updateDto = record.toOrderUpdateDto(periodCode);
        if (updateDto != null) {
            recordUpdate(updateDto, period.getId(), rateBeforeChange);
        }

        return site;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderUpdate> getUpdatesByPeriod(String periodCode) {
        Optional<BillingPeriod> periodOpt = repositorioPeriodo.findByCode(periodCode);
        return periodOpt.map(p -> repositorioNovedad.findByPeriodId(p.getId()))
                .orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderUpdate> getUpdatesByPeriodAndSite(UUID periodId, UUID siteId) {
        return repositorioNovedad.findBySiteIdAndPeriodId(siteId, periodId);
    }

    private BillingPeriod findOrCreatePeriod(String periodCode) {
        return repositorioPeriodo.findByCode(periodCode).orElseGet(() -> {
            log.info("Billing period '{}' not found in database. Auto-creating default period.", periodCode);
            YearMonth ym = YearMonth.parse(periodCode);
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();
            BillingPeriod period = new BillingPeriod(
                    null,
                    periodCode,
                    BillingCutType.ARREARS,
                    start,
                    end,
                    true
            );
            return repositorioPeriodo.save(period);
        });
    }
}
