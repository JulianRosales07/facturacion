package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.InstallationFee;
import com.sistemaspalacios.billing.domain.model.InstallationFeeStatus;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoicePdfHistory;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.NoveltyProcessingStatus;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import com.sistemaspalacios.billing.domain.model.RecordStatus;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.service.BillingCalculator;
import com.sistemaspalacios.billing.domain.service.NoveltyAdjustmentCalculator;
import com.sistemaspalacios.billing.domain.vo.DateRange;
import java.time.LocalDate;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioCuotaInstalacion;
import com.sistemaspalacios.billing.repository.RepositorioDocumentoAjuste;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.service.dto.InvoiceRefreshResult;
import com.sistemaspalacios.billing.repository.RepositorioHistoricoPdfFactura;
import com.sistemaspalacios.billing.repository.RepositorioNovedad;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import com.sistemaspalacios.billing.repository.RepositorioVigenciaConcepto;
import com.sistemaspalacios.billing.service.metrics.BillingMetricsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of BillingService / ServicioFacturacion (Sprint 4).
 * Orchestrates domain services, persistence, invariants, and unique constraints.
 */
@Service
@Transactional
public class BillingServiceImpl implements BillingService, ServicioFacturacion {

    private static final Logger log = LoggerFactory.getLogger(BillingServiceImpl.class);

    /** Base del consecutivo provisional de facturación. */
    private static final long INVOICE_NUMBER_BASE = 51950L;

    private final RepositorioFactura repositorioFactura;
    private final RepositorioCliente repositorioCliente;
    private final RepositorioPeriodo repositorioPeriodo;
    private final RepositorioSede repositorioSede;
    private final RepositorioNovedad repositorioNovedad;
    private final RepositorioCuotaInstalacion repositorioCuotaInstalacion;
    private final RepositorioVigenciaConcepto repositorioVigenciaConcepto;
    private final BillingCalculator billingCalculator;
    private final SiigoInvoiceGraphicRenderer graphicRenderer;
    private final BillingMetricsPort billingMetrics;
    private final com.sistemaspalacios.billing.service.security.CurrentUserProvider currentUserProvider;
    /** Opcional: si no está disponible, la regeneración no archiva el PDF anterior. */
    private final RepositorioHistoricoPdfFactura repositorioHistoricoPdf;

    /** Para no modificar facturas con pagos o notas crédito (RN-07). Inyección opcional. */
    private RepositorioPago repositorioPago;
    private RepositorioDocumentoAjuste repositorioDocumentoAjuste;

    @Autowired(required = false)
    void setEditabilityRepositories(RepositorioPago repositorioPago, RepositorioDocumentoAjuste repositorioDocumentoAjuste) {
        this.repositorioPago = repositorioPago;
        this.repositorioDocumentoAjuste = repositorioDocumentoAjuste;
    }

    @Autowired
    public BillingServiceImpl(
            RepositorioFactura repositorioFactura,
            RepositorioCliente repositorioCliente,
            RepositorioPeriodo repositorioPeriodo,
            RepositorioSede repositorioSede,
            RepositorioNovedad repositorioNovedad,
            RepositorioCuotaInstalacion repositorioCuotaInstalacion,
            RepositorioVigenciaConcepto repositorioVigenciaConcepto,
            SiigoInvoiceGraphicRenderer graphicRenderer,
            @Autowired(required = false) BillingMetricsPort billingMetrics,
            @Autowired(required = false) com.sistemaspalacios.billing.service.security.CurrentUserProvider currentUserProvider,
            @Autowired(required = false) RepositorioHistoricoPdfFactura repositorioHistoricoPdf
    ) {
        this(
                repositorioFactura,
                repositorioCliente,
                repositorioPeriodo,
                repositorioSede,
                repositorioNovedad,
                repositorioCuotaInstalacion,
                repositorioVigenciaConcepto,
                new BillingCalculator(),
                graphicRenderer,
                billingMetrics,
                currentUserProvider,
                repositorioHistoricoPdf
        );
    }

    public BillingServiceImpl(
            RepositorioFactura repositorioFactura,
            RepositorioCliente repositorioCliente,
            RepositorioPeriodo repositorioPeriodo,
            RepositorioSede repositorioSede,
            RepositorioNovedad repositorioNovedad,
            RepositorioCuotaInstalacion repositorioCuotaInstalacion,
            RepositorioVigenciaConcepto repositorioVigenciaConcepto,
            BillingCalculator billingCalculator,
            SiigoInvoiceGraphicRenderer graphicRenderer
    ) {
        this(
                repositorioFactura,
                repositorioCliente,
                repositorioPeriodo,
                repositorioSede,
                repositorioNovedad,
                repositorioCuotaInstalacion,
                repositorioVigenciaConcepto,
                billingCalculator,
                graphicRenderer,
                null,
                null,
                null
        );
    }

    public BillingServiceImpl(
            RepositorioFactura repositorioFactura,
            RepositorioCliente repositorioCliente,
            RepositorioPeriodo repositorioPeriodo,
            RepositorioSede repositorioSede,
            RepositorioNovedad repositorioNovedad,
            RepositorioCuotaInstalacion repositorioCuotaInstalacion,
            RepositorioVigenciaConcepto repositorioVigenciaConcepto,
            BillingCalculator billingCalculator,
            SiigoInvoiceGraphicRenderer graphicRenderer,
            BillingMetricsPort billingMetrics,
            com.sistemaspalacios.billing.service.security.CurrentUserProvider currentUserProvider
    ) {
        this(
                repositorioFactura,
                repositorioCliente,
                repositorioPeriodo,
                repositorioSede,
                repositorioNovedad,
                repositorioCuotaInstalacion,
                repositorioVigenciaConcepto,
                billingCalculator,
                graphicRenderer,
                billingMetrics,
                currentUserProvider,
                null
        );
    }

    public BillingServiceImpl(
            RepositorioFactura repositorioFactura,
            RepositorioCliente repositorioCliente,
            RepositorioPeriodo repositorioPeriodo,
            RepositorioSede repositorioSede,
            RepositorioNovedad repositorioNovedad,
            RepositorioCuotaInstalacion repositorioCuotaInstalacion,
            RepositorioVigenciaConcepto repositorioVigenciaConcepto,
            BillingCalculator billingCalculator,
            SiigoInvoiceGraphicRenderer graphicRenderer,
            BillingMetricsPort billingMetrics,
            com.sistemaspalacios.billing.service.security.CurrentUserProvider currentUserProvider,
            RepositorioHistoricoPdfFactura repositorioHistoricoPdf
    ) {
        this.repositorioFactura = Objects.requireNonNull(repositorioFactura, "repositorioFactura cannot be null");
        this.repositorioCliente = Objects.requireNonNull(repositorioCliente, "repositorioCliente cannot be null");
        this.repositorioPeriodo = Objects.requireNonNull(repositorioPeriodo, "repositorioPeriodo cannot be null");
        this.repositorioSede = Objects.requireNonNull(repositorioSede, "repositorioSede cannot be null");
        this.repositorioNovedad = Objects.requireNonNull(repositorioNovedad, "repositorioNovedad cannot be null");
        this.repositorioCuotaInstalacion = Objects.requireNonNull(repositorioCuotaInstalacion, "repositorioCuotaInstalacion cannot be null");
        this.repositorioVigenciaConcepto = Objects.requireNonNull(repositorioVigenciaConcepto, "repositorioVigenciaConcepto cannot be null");
        this.billingCalculator = billingCalculator != null ? billingCalculator : new BillingCalculator();
        this.graphicRenderer = graphicRenderer != null ? graphicRenderer : new SiigoInvoiceGraphicRenderer();
        this.billingMetrics = billingMetrics;
        this.currentUserProvider = currentUserProvider;
        this.repositorioHistoricoPdf = repositorioHistoricoPdf;
    }

    @Override
    public Invoice generateConsolidatedInvoice(String periodCode, BillingType billingType, BillingCutType cutType, String nit) {
        if (nit == null || nit.isBlank()) {
            throw new IllegalArgumentException("Se requiere el NIT del cliente para generar una factura individual");
        }
        List<Invoice> generated = generateConsolidated(periodCode, billingType, cutType, nit.trim());
        if (generated.isEmpty()) {
            throw new IllegalStateException("No se pudo generar la factura consolidada para el cliente con NIT " + nit);
        }
        return generated.get(0);
    }

    @Override
    public List<Invoice> generateConsolidated(String periodCode, BillingType billingType, BillingCutType cutType, String nit) {
        if (periodCode == null || periodCode.isBlank()) {
            throw new IllegalArgumentException("El código del periodo de facturación es obligatorio");
        }

        BillingPeriod period = repositorioPeriodo.findByCode(periodCode.trim())
                .orElseThrow(() -> new NoSuchElementException("No se encontró el periodo de facturación: " + periodCode));

        List<CorporateClient> clientsToProcess = new ArrayList<>();
        if (nit != null && !nit.isBlank()) {
            CorporateClient client = repositorioCliente.findByNit(nit.trim())
                    .orElseThrow(() -> new NoSuchElementException("Cliente corporativo no encontrado con NIT: " + nit));
            clientsToProcess.add(client);
        } else {
            clientsToProcess.addAll(repositorioCliente.findByStatus(RecordStatus.ACTIVO));
        }

        if (clientsToProcess.isEmpty()) {
            throw new IllegalStateException("No hay clientes corporativos activos para procesar en este corte");
        }

        List<Invoice> resultInvoices = new ArrayList<>();
        List<OrderUpdate> allUpdates = repositorioNovedad.findByPeriodId(period.getId());
        List<TaxConceptValidity> taxValidities = repositorioVigenciaConcepto.findActiveAtDate(period.getEndDate());

        long currentCount = repositorioFactura.count();

        boolean isBatchMode = (nit == null || nit.isBlank());

        for (CorporateClient client : clientsToProcess) {
            try {
                // 1. Validar unicidad vigente (HU-08): No permitir dos facturas vigentes para el mismo
                // cliente + periodo. Desde Sprint 10 "vigente" excluye también las reemplazadas,
                // de modo que las versiones históricas no bloquean la emisión.
                Optional<Invoice> activeInvoiceOpt = repositorioFactura.findCurrentByClientAndPeriod(client.getId(), period.getId());
                if (activeInvoiceOpt.isPresent()) {
                    throw new FacturaActivaYaExisteException(client.getNit(), period.getCode(), activeInvoiceOpt.get().getId());
                }

                // 2. Cargar sedes facturables: activas más las dadas de baja DENTRO del periodo,
                // cuyos días consumidos antes de la baja también deben cobrarse (Sprint 10).
                List<SubscriptionSite> sites = loadBillableSites(client.getId(), period, allUpdates);
                if (sites.isEmpty()) {
                    if (!isBatchMode) {
                        throw new IllegalStateException("El cliente corporativo con NIT " + client.getNit() + " no tiene sedes activas facturables");
                    }
                    log.info("Batch generation: Skipping client {} ({}) because it has no active sites", client.getNit(), client.getBusinessName());
                    continue; // En lote masivo omitimos clientes sin sedes
                }

                // 3. Cargar novedades de las sedes
                List<UUID> siteIds = sites.stream().map(SubscriptionSite::getId).toList();
                // Solo las que no son ajustes de un periodo emitido ni se cobraron ya en otra factura.
                List<OrderUpdate> clientUpdates = eligibleForOwnInvoice(allUpdates.stream()
                        .filter(u -> siteIds.contains(u.getSiteId()))
                        .toList(), Set.of());

                // 4. Cargar cuotas de instalación pendientes
                List<InstallationFee> clientFees = new ArrayList<>();
                for (SubscriptionSite site : sites) {
                    clientFees.addAll(repositorioCuotaInstalacion.findBySiteIdAndStatus(site.getId(), InstallationFeeStatus.PENDING));
                    clientFees.addAll(repositorioCuotaInstalacion.findBySiteIdAndStatus(site.getId(), InstallationFeeStatus.BILLING));
                }

                // 5. Invocar servicio de dominio para calcular la consolidación (HU-10, HU-12, HU-13, HU-18)
                // Las tarifas de apertura vienen de la factura del periodo anterior: si un cambio
                // de plan llegó antes de esta primera emisión, sede.valor_mensual ya trae la tarifa
                // nueva y sin este dato el tramo previo al cambio se cobraría mal.
                Invoice invoice = billingCalculator.calculateConsolidatedInvoice(
                        client,
                        period,
                        sites,
                        clientUpdates,
                        clientFees,
                        taxValidities,
                        resolveOpeningRates(client.getId(), period, null)
                );
                // Ajustes de periodos anteriores ya emitidos o pagados (escenarios C y D).
                List<OrderUpdate> adjustments = applyPendingAdjustments(invoice, client, period, Set.of());

                // Validar invariante de negocio
                invoice.assertCanBeIssued();

                // Asignar numeración provisional oficial
                BillingType effectiveBillingType = billingType != null ? billingType : client.getBillingType();
                String prefix = (effectiveBillingType == BillingType.F20) ? "FEMG" : "FESP";
                long seq = INVOICE_NUMBER_BASE + (++currentCount);
                invoice.setInvoiceNumber(prefix + " " + seq);

                // Asignar usuario de auditoría (Sprint 8, HU-34 / RF-33)
                String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";
                invoice.setCreatedBy(currentUser);

                // Transición a CONSOLIDADA
                invoice.consolidate();

                // Persistir factura e ítems
                Invoice savedInvoice = repositorioFactura.saveAndFlush(invoice);

                // Actualizar estado de cuotas facturadas
                if (!clientFees.isEmpty()) {
                    repositorioCuotaInstalacion.saveAll(clientFees);
                }

                // Trazabilidad novedad -> factura (Sprint 10): deja constancia de qué novedades
                // alimentaron esta versión y evita que se vuelvan a aplicar sin control.
                markUpdatesAsBilled(clientUpdates, savedInvoice.getId());
                markAdjustmentsApplied(adjustments, savedInvoice.getId());

                log.info("Factura {} generada exitosamente para cliente NIT {} por usuario '{}'",
                        savedInvoice.getInvoiceNumber(), client.getNit(), currentUser);

                if (billingMetrics != null) {
                    billingMetrics.recordInvoiceGenerated(effectiveBillingType.name(), true);
                }

                resultInvoices.add(savedInvoice);
            } catch (Exception ex) {
                if (!isBatchMode) {
                    if (billingMetrics != null) {
                        billingMetrics.recordInvoiceGenerated(client.getBillingType().name(), false);
                    }
                    throw ex;
                }
                log.warn("Batch resilience: Skipping client {} ({}) due to error: {}", client.getNit(), client.getBusinessName(), ex.getMessage());
                if (billingMetrics != null) {
                    billingMetrics.recordInvoiceGenerated(client.getBillingType().name(), false);
                }
            }
        }

        return resultInvoices;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invoice> getInvoices(String periodCode, String nit, InvoiceStatus status) {
        List<Invoice> invoices;

        if (periodCode != null && !periodCode.isBlank()) {
            Optional<BillingPeriod> periodOpt = repositorioPeriodo.findByCode(periodCode.trim());
            if (periodOpt.isPresent()) {
                invoices = repositorioFactura.findByPeriodId(periodOpt.get().getId());
            } else {
                invoices = new ArrayList<>();
            }
        } else {
            invoices = repositorioFactura.findAll();
        }

        if (nit != null && !nit.isBlank()) {
            String searchNit = nit.trim().toLowerCase();
            invoices = invoices.stream()
                    .filter(i -> i.getClientNit().toLowerCase().contains(searchNit) ||
                            (i.getClientBusinessName() != null && i.getClientBusinessName().toLowerCase().contains(searchNit)))
                    .toList();
        }

        if (status != null) {
            invoices = invoices.stream()
                    .filter(i -> i.getStatus() == status)
                    .toList();
        }

        return invoices;
    }

    @Override
    @Transactional(readOnly = true)
    public Invoice getInvoiceById(UUID invoiceId) {
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        return repositorioFactura.findById(invoiceId)
                .orElseThrow(() -> new NoSuchElementException("Factura no encontrada con ID: " + invoiceId));
    }

    @Override
    public void voidInvoice(UUID invoiceId, String reason) {
        Invoice invoice = getInvoiceById(invoiceId);
        String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";
        invoice.voidInvoice(reason, currentUser);
        repositorioFactura.saveAndFlush(invoice);

        // La factura deja de ser vigente: sus novedades vuelven al pool de pendientes para
        // que una emisión posterior del mismo periodo las recoja (Sprint 10).
        int released = repositorioNovedad.releaseByInvoiceId(invoiceId);
        if (released > 0) {
            log.info("Anulación de factura {}: {} novedades liberadas y disponibles para refacturar", invoiceId, released);
        }

        log.info("Factura ID {} anulada por usuario '{}'. Motivo: '{}'", invoiceId, currentUser, reason);
    }

    // ===========================================================================
    // Regeneración versionada (Sprint 10)
    // ===========================================================================

    @Override
    public Invoice regenerateInvoice(UUID invoiceId, String reason, boolean confirmTransmittedCufe) {
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("El motivo de la regeneración es obligatorio");
        }
        String regenerationReason = reason.trim();
        String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";

        Invoice previous = getInvoiceById(invoiceId);

        // 1. Guardas de estado y fiscales
        if (!previous.canBeRegenerated()) {
            try {
                previous.assertCanBeRegenerated();
                throw FacturaNoRegenerableException.dueToState(invoiceId, "La factura no admite regeneración");
            } catch (IllegalStateException ex) {
                throw FacturaNoRegenerableException.dueToState(invoiceId, ex.getMessage());
            }
        }
        if (previous.getCufe() != null && !previous.getCufe().isBlank() && !confirmTransmittedCufe) {
            throw FacturaNoRegenerableException.dueToTransmittedCufe(
                    invoiceId, previous.getInvoiceNumber(), previous.getCufe());
        }

        // 2. Contexto del mismo cliente y el mismo periodo: la nueva versión acumula
        // todo lo del mes, no solo lo que llegó después.
        CorporateClient client = repositorioCliente.findById(previous.getClientId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Cliente corporativo no encontrado con ID: " + previous.getClientId()));
        BillingPeriod period = repositorioPeriodo.findById(previous.getPeriodId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Periodo de facturación no encontrado con ID: " + previous.getPeriodId()));

        List<OrderUpdate> allPeriodUpdates = repositorioNovedad.findByPeriodId(period.getId());

        List<SubscriptionSite> sites = loadBillableSites(client.getId(), period, allPeriodUpdates);
        if (sites.isEmpty()) {
            throw new IllegalStateException("El cliente corporativo con NIT " + client.getNit()
                    + " no tiene sedes facturables en el periodo; no es posible regenerar la factura");
        }
        List<UUID> siteIds = sites.stream().map(SubscriptionSite::getId).toList();

        List<OrderUpdate> periodUpdates = eligibleForOwnInvoice(allPeriodUpdates.stream()
                .filter(u -> siteIds.contains(u.getSiteId()))
                .toList(), Set.of(previous.getId()));

        List<TaxConceptValidity> taxValidities = repositorioVigenciaConcepto.findActiveAtDate(period.getEndDate());

        // 3. Liberar las cuotas de instalación que consumió la versión anterior. Sin esto,
        // la nueva versión avanzaría el contador otra vez y el cliente pagaría dos veces
        // la misma cuota.
        List<InstallationFee> rolledBackFees = rollbackInstallationFees(previous, sites);

        // 4. Recalcular desde cero con el estado actual de sedes y novedades
        List<InstallationFee> feesForBilling = loadBillableFees(sites);
        Invoice newVersion = billingCalculator.calculateConsolidatedInvoice(
                client, period, sites, periodUpdates, feesForBilling, taxValidities,
                resolveOpeningRates(client.getId(), period, previous));
        // Los ajustes que llevaba la versión anterior pasan a la nueva, sin sumarse otra vez.
        List<OrderUpdate> adjustments = applyPendingAdjustments(newVersion, client, period, Set.of(previous.getId()));

        newVersion.assertCanBeIssued();
        newVersion.markAsNextVersionOf(previous, regenerationReason);
        newVersion.setResidential(previous.isResidential());
        carryOverExtraFields(previous, newVersion);

        BillingType effectiveBillingType = previous.getBillingType() != null
                ? previous.getBillingType()
                : client.getBillingType();
        newVersion.setInvoiceNumber(nextInvoiceNumber(effectiveBillingType));
        newVersion.setCreatedBy(currentUser);
        newVersion.consolidate();

        // 5. Cerrar la versión anterior ANTES de insertar la nueva: el índice parcial
        // uq_factura_cliente_periodo_activa solo admite una factura vigente por
        // cliente + periodo. La FK reemplazada_por_id es DEFERRABLE INITIALLY DEFERRED,
        // por lo que apuntar a una fila aún no insertada es válido hasta el commit.
        archivePreviousPdf(previous, regenerationReason, currentUser);
        previous.supersede(newVersion.getId(), regenerationReason, currentUser);
        repositorioFactura.saveAndFlush(previous);

        Invoice saved = repositorioFactura.saveAndFlush(newVersion);

        // 6. Persistir efectos laterales
        List<InstallationFee> feesToPersist = new ArrayList<>(rolledBackFees);
        for (InstallationFee fee : feesForBilling) {
            if (!feesToPersist.contains(fee)) {
                feesToPersist.add(fee);
            }
        }
        if (!feesToPersist.isEmpty()) {
            repositorioCuotaInstalacion.saveAll(feesToPersist);
        }
        markUpdatesAsBilled(periodUpdates, saved.getId());
        markAdjustmentsApplied(adjustments, saved.getId());

        if (billingMetrics != null) {
            billingMetrics.recordInvoiceGenerated(effectiveBillingType.name(), true);
        }

        log.info("Factura {} regenerada: versión {} -> {} (nueva factura {}, total {} -> {}) por usuario '{}'. Motivo: '{}'",
                previous.getInvoiceNumber(),
                previous.getVersionNumber(),
                saved.getVersionNumber(),
                saved.getInvoiceNumber(),
                previous.getTotalAmount(),
                saved.getTotalAmount(),
                currentUser,
                regenerationReason);

        return saved;
    }

    @Override
    public InvoiceRefreshResult refreshEditableInvoice(UUID clientId, UUID periodId) {
        Objects.requireNonNull(clientId, "clientId cannot be null");
        Objects.requireNonNull(periodId, "periodId cannot be null");

        BillingPeriod period = repositorioPeriodo.findById(periodId)
                .orElseThrow(() -> new NoSuchElementException("Periodo de facturación no encontrado con ID: " + periodId));
        CorporateClient client = repositorioCliente.findById(clientId)
                .orElseThrow(() -> new NoSuchElementException("Cliente corporativo no encontrado con ID: " + clientId));

        // RN-01 / RN-06: la factura se busca por cliente y periodo; el índice
        // uq_factura_cliente_periodo_activa garantiza que hay a lo sumo una interna vigente.
        // La fila queda bloqueada hasta el fin de la transacción (concurrencia).
        Optional<Invoice> currentOpt = repositorioFactura.lockCurrentByClientAndPeriod(clientId, periodId);
        if (currentOpt.isPresent()) {
            Invoice invoice = currentOpt.get();
            String blocker = editabilityBlocker(invoice);
            if (blocker == null) {
                return recalculateInPlace(invoice, client, period);   // Escenario B
            }
            // Escenarios C y D: emitida o pagada. El documento no se toca.
            log.warn("Novedades del cliente {} en {} no aplicadas a la factura {}: {}",
                    client.getNit(), period.getCode(), invoice.getInvoiceNumber(), blocker);
            String note = deferToNextInvoice(client, period, "La factura " + invoice.getInvoiceNumber() + " " + blocker);
            return new InvoiceRefreshResult(InvoiceRefreshResult.Outcome.NO_EDITABLE, invoice.getId(),
                    invoice.getInvoiceNumber(), invoice.getTotalAmount(), invoice.getTotalAmount(),
                    invoice.getItems().size(), blocker + note);
        }

        // El periodo ya se facturó directamente en Siigo: generar otra factura lo cobraría dos veces.
        Optional<Invoice> official = currentOfficialDocument(clientId, periodId);
        if (official.isPresent()) {
            Invoice doc = official.get();
            String reason = "el periodo " + period.getCode() + " ya fue facturado en Siigo con el documento " + doc.getInvoiceNumber();
            String note = deferToNextInvoice(client, period, reason);
            return new InvoiceRefreshResult(InvoiceRefreshResult.Outcome.NO_EDITABLE, doc.getId(), doc.getInvoiceNumber(),
                    doc.getTotalAmount(), doc.getTotalAmount(), doc.getItems().size(), reason + note);
        }

        // Escenario A: aún no hay documento del periodo; las novedades entran al generarlo.
        return new InvoiceRefreshResult(InvoiceRefreshResult.Outcome.SIN_FACTURA, null, null,
                Money.ZERO, Money.ZERO, 0,
                "El cliente " + client.getNit() + " aún no tiene factura del periodo " + period.getCode()
                        + "; las novedades se incluirán al generarla.");
    }

    /** Escenario B: recalcula la factura en preparación con las novedades de su periodo y los ajustes pendientes. */
    private InvoiceRefreshResult recalculateInPlace(Invoice invoice, CorporateClient client, BillingPeriod period) {
        Money previousTotal = invoice.getTotalAmount();
        Set<UUID> ownInvoiceIds = Set.of(invoice.getId());

        List<OrderUpdate> allPeriodUpdates = eligibleForOwnInvoice(repositorioNovedad.findByPeriodId(period.getId()), ownInvoiceIds);
        List<SubscriptionSite> sites = loadBillableSites(client.getId(), period, allPeriodUpdates);
        if (sites.isEmpty()) {
            String reason = "el cliente ya no tiene sedes facturables en el periodo";
            return new InvoiceRefreshResult(InvoiceRefreshResult.Outcome.NO_EDITABLE, invoice.getId(),
                    invoice.getInvoiceNumber(), previousTotal, previousTotal, invoice.getItems().size(), reason);
        }
        List<UUID> siteIds = sites.stream().map(SubscriptionSite::getId).toList();
        List<OrderUpdate> periodUpdates = allPeriodUpdates.stream()
                .filter(u -> siteIds.contains(u.getSiteId()))
                .toList();
        List<TaxConceptValidity> taxValidities = repositorioVigenciaConcepto.findActiveAtDate(period.getEndDate());

        // La tarifa de apertura sale de la propia factura antes de tocarla.
        Map<UUID, Money> openingRates = resolveOpeningRates(client.getId(), period, invoice);

        // RN-04 / RN-05: se recalcula todo el periodo desde las novedades registradas, no se
        // suma sobre el total anterior. Procesar dos veces la misma novedad da el mismo
        // resultado. Las cuotas que consumió la factura se liberan antes, igual que al
        // regenerar, para no avanzarlas dos veces.
        List<InstallationFee> rolledBackFees = rollbackInstallationFees(invoice, sites);
        List<InstallationFee> feesForBilling = loadBillableFees(sites);
        Invoice recalculated = billingCalculator.calculateConsolidatedInvoice(
                client, period, sites, periodUpdates, feesForBilling, taxValidities, openingRates);

        invoice.replaceItems(new ArrayList<>(recalculated.getItems()));
        List<OrderUpdate> adjustments = applyPendingAdjustments(invoice, client, period, ownInvoiceIds);
        Invoice saved = repositorioFactura.saveAndFlush(invoice);

        List<InstallationFee> feesToPersist = new ArrayList<>(rolledBackFees);
        feesForBilling.stream().filter(f -> !feesToPersist.contains(f)).forEach(feesToPersist::add);
        if (!feesToPersist.isEmpty()) {
            repositorioCuotaInstalacion.saveAll(feesToPersist);
        }
        markUpdatesAsBilled(periodUpdates, saved.getId());
        markAdjustmentsApplied(adjustments, saved.getId());

        log.info("Factura {} del cliente {} ({}) actualizada en preparación con {} novedades y {} ajustes: total {} -> {}",
                saved.getInvoiceNumber(), client.getNit(), period.getCode(), periodUpdates.size(), adjustments.size(),
                previousTotal, saved.getTotalAmount());

        return new InvoiceRefreshResult(InvoiceRefreshResult.Outcome.ACTUALIZADA, saved.getId(),
                saved.getInvoiceNumber(), previousTotal, saved.getTotalAmount(), saved.getItems().size(), null);
    }

    /**
     * Novedades que pueden entrar en la factura de su propio periodo: no son ajustes de un
     * periodo ya emitido y no están aplicadas en otra factura vigente (una novedad se cobra
     * en un solo documento).
     */
    private List<OrderUpdate> eligibleForOwnInvoice(List<OrderUpdate> updates, Set<UUID> ownInvoiceIds) {
        Map<UUID, Boolean> currentById = new HashMap<>();
        return updates.stream()
                .filter(OrderUpdate::isAwaitingOwnInvoice)
                .filter(u -> u.getInvoiceId() == null || ownInvoiceIds.contains(u.getInvoiceId())
                        || !currentById.computeIfAbsent(u.getInvoiceId(),
                                id -> repositorioFactura.findById(id).map(i -> i.getStatus().isCurrent()).orElse(false)))
                .toList();
    }

    /**
     * Escenarios C y D: las novedades del periodo que aún no se facturaron quedan como ajuste
     * pendiente con su impacto calculado, y se incorporan de una vez a la siguiente factura en
     * preparación del cliente si ya existe. Devuelve una nota para el mensaje al usuario.
     */
    private String deferToNextInvoice(CorporateClient client, BillingPeriod period, String reason) {
        Set<UUID> siteIds = repositorioSede.findByClientId(client.getId()).stream()
                .map(SubscriptionSite::getId).collect(java.util.stream.Collectors.toSet());
        DateRange range = new DateRange(period.getStartDate(), period.getEndDate());
        List<OrderUpdate> deferred = new ArrayList<>();
        for (OrderUpdate update : repositorioNovedad.findByPeriodId(period.getId())) {
            if (!siteIds.contains(update.getSiteId()) || !update.isIncludeInConsolidation()
                    || update.getProcessingStatus() != NoveltyProcessingStatus.PENDIENTE || update.getInvoiceId() != null) {
                continue;
            }
            Money impact = adjustmentCalculator.impactOf(update, range);
            String detail = reason + (impact == null
                    ? ". Impacto no calculable: requiere revisión manual"
                    : ". Ajuste de " + impact.amount().toPlainString() + " para la siguiente factura");
            update.markPendingAdjustment(impact, detail);
            deferred.add(update);
        }
        if (deferred.isEmpty()) {
            return "";
        }
        repositorioNovedad.saveAll(deferred);

        Optional<InvoiceRefreshResult> next = refreshNextEditableInvoice(client, period);
        return next.map(r -> ". " + deferred.size() + " novedad(es) pasan como ajuste a la factura " + r.invoiceNumber()
                        + " (total " + r.previousTotal().amount().toPlainString() + " → " + r.newTotal().amount().toPlainString() + ")")
                .orElse(". " + deferred.size() + " novedad(es) quedan como ajuste para la próxima factura del cliente");
    }

    /** La siguiente factura en preparación del cliente (periodo posterior), recalculada con los ajustes. */
    private Optional<InvoiceRefreshResult> refreshNextEditableInvoice(CorporateClient client, BillingPeriod period) {
        Map<UUID, BillingPeriod> periods = new HashMap<>();
        return repositorioFactura.findByClientId(client.getId()).stream()
                .filter(i -> !i.isExternalDocument() && i.getStatus().isCurrent())
                .filter(i -> {
                    BillingPeriod p = periods.computeIfAbsent(i.getPeriodId(), id -> repositorioPeriodo.findById(id).orElse(null));
                    return p != null && p.getStartDate().isAfter(period.getStartDate());
                })
                .min(java.util.Comparator.comparing(i -> periods.get(i.getPeriodId()).getStartDate()))
                .flatMap(i -> repositorioFactura.lockCurrentByClientAndPeriod(client.getId(), i.getPeriodId()))
                .filter(i -> editabilityBlocker(i) == null)
                .map(i -> recalculateInPlace(i, client, periods.get(i.getPeriodId())));
    }

    /** Documento oficial de Siigo vigente del cliente para el periodo (facturado fuera de este sistema). */
    private Optional<Invoice> currentOfficialDocument(UUID clientId, UUID periodId) {
        return repositorioFactura.findByClientId(clientId).stream()
                .filter(i -> i.isExternalDocument() && periodId.equals(i.getPeriodId()) && i.getStatus().isCurrent())
                .findFirst();
    }

    /**
     * Ajustes de periodos anteriores que deben entrar en esta factura: los pendientes con
     * impacto calculado y los que ya estaban en ella (al recalcular se vuelven a aplicar, no se
     * suman otra vez). Devuelve las novedades aplicadas para marcarlas.
     */
    private List<OrderUpdate> applyPendingAdjustments(Invoice invoice, CorporateClient client, BillingPeriod period,
                                                      Set<UUID> ownInvoiceIds) {
        List<SubscriptionSite> clientSites = repositorioSede.findByClientId(client.getId());
        Map<UUID, SubscriptionSite> sitesById = new HashMap<>();
        clientSites.forEach(s -> sitesById.put(s.getId(), s));
        if (sitesById.isEmpty()) {
            return List.of();
        }
        Map<UUID, BillingPeriod> periods = new HashMap<>();
        List<OrderUpdate> applicable = repositorioNovedad.findBySiteIdInAndProcessingStatusIn(sitesById.keySet(),
                        List.of(NoveltyProcessingStatus.PENDIENTE_AJUSTE, NoveltyProcessingStatus.AJUSTE_APLICADO)).stream()
                .filter(u -> u.getImpactAmount() != null && !u.getImpactAmount().isZero())
                .filter(u -> u.getProcessingStatus() == NoveltyProcessingStatus.PENDIENTE_AJUSTE
                        ? u.getInvoiceId() == null
                        : ownInvoiceIds.contains(u.getInvoiceId()))
                .filter(u -> {
                    BillingPeriod p = periods.computeIfAbsent(u.getPeriodId(), id -> repositorioPeriodo.findById(id).orElse(null));
                    return p != null && p.getStartDate().isBefore(period.getStartDate());
                })
                .sorted(java.util.Comparator.comparing(OrderUpdate::getEffectiveDate,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .toList();
        if (applicable.isEmpty()) {
            return List.of();
        }

        String municipality = client.getMunicipality() != null ? client.getMunicipality() : "GENERAL";
        List<NoveltyAdjustmentCalculator.Adjustment> adjustments = new ArrayList<>();
        for (OrderUpdate u : applicable) {
            BillingPeriod p = periods.get(u.getPeriodId());
            LocalDate from = u.getEffectiveDate() == null || u.getEffectiveDate().isBefore(p.getStartDate())
                    ? p.getStartDate() : u.getEffectiveDate();
            SubscriptionSite site = sitesById.get(u.getSiteId());
            String siteName = site != null && site.getSiteName() != null ? site.getSiteName() : "SEDE";
            String description = billingCalculator.formatItemDescription(municipality, "CORP",
                    siteName + " AJUSTE " + u.getUpdateType().name().replace('_', ' ') + " PERIODO " + p.getCode(),
                    new DateRange(from, p.getEndDate()));
            adjustments.add(new NoveltyAdjustmentCalculator.Adjustment(u.getId(), u.getSiteId(), description,
                    from, p.getEndDate(), u.getImpactAmount()));
        }
        Money uncompensated = adjustmentCalculator.apply(invoice, adjustments);
        if (uncompensated.isPositive()) {
            log.warn("Factura {}: {} a favor del cliente {} no alcanzaron a compensarse; quedan como saldo a favor",
                    invoice.getInvoiceNumber(), uncompensated, client.getNit());
        }
        return applicable;
    }

    private void markAdjustmentsApplied(List<OrderUpdate> adjustments, UUID invoiceId) {
        if (adjustments.isEmpty()) {
            return;
        }
        adjustments.forEach(u -> u.markAdjustmentApplied(invoiceId));
        repositorioNovedad.saveAll(adjustments);
    }

    private final NoveltyAdjustmentCalculator adjustmentCalculator = new NoveltyAdjustmentCalculator();


    /** RN-07: reglas del dominio más pagos y notas crédito, que viven en otros agregados. */
    private String editabilityBlocker(Invoice invoice) {
        String blocker = invoice.editabilityBlocker();
        if (blocker != null) {
            return blocker;
        }
        // La factura se cobra en la pasarela: desde que el cliente inicia el pago, su valor no
        // puede cambiar. Un intento rechazado o anulado no la congela.
        if (repositorioPago != null && !repositorioPago.findAppliedPaymentsByInvoiceId(invoice.getId()).isEmpty()) {
            return "tiene pagos aplicados";
        }
        if (repositorioPago != null && !repositorioPago.findPendingPaymentsByInvoiceIds(List.of(invoice.getId())).isEmpty()) {
            return "tiene un pago en curso en la pasarela";
        }
        if (repositorioDocumentoAjuste != null && !repositorioDocumentoAjuste.findByInvoiceId(invoice.getId()).isEmpty()) {
            return "tiene notas crédito o débito asociadas";
        }
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invoice> getInvoiceVersions(UUID invoiceId) {
        Invoice invoice = getInvoiceById(invoiceId);
        List<Invoice> chain = repositorioFactura.findByOriginInvoiceIdOrderByVersionNumberAsc(invoice.getOriginInvoiceId());
        return chain.isEmpty() ? List.of(invoice) : chain;
    }

    @Override
    @Transactional(readOnly = true)
    public Invoice getCurrentInvoiceForClientPeriod(String nit, String periodCode) {
        if (nit == null || nit.isBlank()) {
            throw new IllegalArgumentException("El NIT del cliente es obligatorio");
        }
        if (periodCode == null || periodCode.isBlank()) {
            throw new IllegalArgumentException("El código del periodo es obligatorio");
        }
        CorporateClient client = repositorioCliente.findByNit(nit.trim())
                .orElseThrow(() -> new NoSuchElementException("Cliente corporativo no encontrado con NIT: " + nit));
        BillingPeriod period = repositorioPeriodo.findByCode(periodCode.trim())
                .orElseThrow(() -> new NoSuchElementException("No se encontró el periodo de facturación: " + periodCode));

        return repositorioFactura.findCurrentByClientAndPeriod(client.getId(), period.getId())
                .orElseThrow(() -> new NoSuchElementException(String.format(
                        "No existe factura vigente para el NIT %s en el periodo %s", nit, periodCode)));
    }

    // --- Helpers de regeneración ------------------------------------------------

    /**
     * Devuelve al pool de pendientes las cuotas de instalación que facturó la versión
     * que se está reemplazando.
     * <p>
     * El calculador emite las cuotas como ítems {@code CORPORATE_SERVICE} cuya descripción
     * empieza por {@value #INSTALLATION_FEE_DESCRIPTION_PREFIX}, así que el conteo se hace
     * por esa marca agrupando por sede. Asume una sola cuota activa por sede, que es la
     * invariante que mantiene la sincronización de órdenes.
     */
    private List<InstallationFee> rollbackInstallationFees(Invoice previous, List<SubscriptionSite> sites) {
        Map<UUID, Integer> consumedBySite = new HashMap<>();
        for (InvoiceItem item : previous.getItems()) {
            if (item.getSiteId() == null || item.getDescription() == null) {
                continue;
            }
            if (item.isInstallationFeeLine()) {
                consumedBySite.merge(item.getSiteId(), 1, Integer::sum);
            }
        }
        if (consumedBySite.isEmpty()) {
            return List.of();
        }

        List<InstallationFee> rolledBack = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : consumedBySite.entrySet()) {
            List<InstallationFee> siteFees = new ArrayList<>();
            siteFees.addAll(repositorioCuotaInstalacion.findBySiteIdAndStatus(entry.getKey(), InstallationFeeStatus.BILLING));
            siteFees.addAll(repositorioCuotaInstalacion.findBySiteIdAndStatus(entry.getKey(), InstallationFeeStatus.PAID));

            int remaining = entry.getValue();
            for (InstallationFee fee : siteFees) {
                if (remaining <= 0) break;
                int releasable = Math.min(remaining, fee.getBilledInstallments());
                if (releasable > 0) {
                    fee.rollbackBilledInstallments(releasable);
                    rolledBack.add(fee);
                    remaining -= releasable;
                }
            }
            if (remaining > 0) {
                log.warn("Regeneración de factura {}: no se pudieron liberar {} cuotas de instalación de la sede {}. "
                                + "Verifique manualmente el avance de cuotas.",
                        previous.getId(), remaining, entry.getKey());
            }
        }
        if (!rolledBack.isEmpty()) {
            repositorioCuotaInstalacion.saveAll(rolledBack);
        }
        return rolledBack;
    }

    /**
     * Sedes que deben entrar en el consolidado del periodo (Sprint 10).
     * <p>
     * Además de las activas, incluye las que quedaron INACTIVAS pero tienen una novedad de
     * baja o suspensión con fecha efectiva dentro del periodo. Sin esto, cuando la
     * sincronización de órdenes desactiva una sede por BAJA, la sede desaparecía de la
     * factura y los días que el cliente sí consumió antes de la baja no se cobraban nunca.
     * Una sede dada de baja en periodos anteriores no tiene novedades aquí y se omite.
     */
    private List<SubscriptionSite> loadBillableSites(UUID clientId, BillingPeriod period, List<OrderUpdate> periodUpdates) {
        List<SubscriptionSite> active = repositorioSede.findByClientIdAndStatus(clientId, RecordStatus.ACTIVO);

        Set<UUID> deactivatedInPeriod = periodUpdates.stream()
                .filter(u -> u.getUpdateType() == OrderUpdateType.BAJA
                        || u.getUpdateType() == OrderUpdateType.SUSPENSION)
                .filter(u -> u.getEffectiveDate() != null
                        && !u.getEffectiveDate().isBefore(period.getStartDate())
                        && !u.getEffectiveDate().isAfter(period.getEndDate()))
                .map(OrderUpdate::getSiteId)
                .collect(java.util.stream.Collectors.toSet());

        if (deactivatedInPeriod.isEmpty()) {
            return active;
        }

        Set<UUID> alreadyIncluded = active.stream().map(SubscriptionSite::getId)
                .collect(java.util.stream.Collectors.toSet());

        List<SubscriptionSite> result = new ArrayList<>(active);
        for (SubscriptionSite site : repositorioSede.findByClientIdAndStatus(clientId, RecordStatus.INACTIVO)) {
            if (deactivatedInPeriod.contains(site.getId()) && !alreadyIncluded.contains(site.getId())) {
                result.add(site);
                log.info("Sede {} está inactiva pero fue dada de baja dentro del periodo {}: se factura el tramo consumido",
                        site.getId(), period.getCode());
            }
        }
        return result;
    }

    /**
     * Tarifa mensual vigente al INICIO del periodo para cada sede (Sprint 10).
     * <p>
     * Resuelve el hueco del cambio de plan a mitad de mes. La sincronización de órdenes
     * actualiza {@code sede_suscripcion.valor_mensual} a la tarifa nueva, de modo que al
     * calcular la factura ya no queda rastro de la anterior y el mes entero se cobraba a la
     * tarifa nueva: de más al subir de plan, de menos al bajar.
     * <p>
     * Precedencia de la fuente:
     * <ol>
     *   <li>La versión anterior de la factura del mismo periodo, cuando se está regenerando.
     *       Es el dato más fiable porque es lo que efectivamente se le facturó.</li>
     *   <li>La factura vigente del periodo inmediatamente anterior, que cubre el caso en que
     *       el cambio de plan llegó antes de la primera emisión del mes.</li>
     *   <li>Vacío: el calculador cae a {@code sede.valor_mensual}.</li>
     * </ol>
     */
    private Map<UUID, Money> resolveOpeningRates(UUID clientId, BillingPeriod period, Invoice previousVersion) {
        Map<UUID, Money> rates = new HashMap<>(resolveOpeningRatesFromInvoices(clientId, period, previousVersion));
        rates.putAll(ratesBeforeFirstChange(period));
        return rates;
    }

    /**
     * La tarifa anterior que guardó la primera novedad de cambio de plan/servicio de cada sede
     * en el periodo (V26). Es la observación directa de la tarifa que regía antes del cambio,
     * por eso manda: no depende de que exista una factura previa ni del orden en que se
     * importó la novedad y se generó la factura. Un cambio efectivo el día 1 o antes no la
     * necesita (no se cobró ningún día al plan anterior).
     */
    private Map<UUID, Money> ratesBeforeFirstChange(BillingPeriod period) {
        Map<UUID, OrderUpdate> first = new HashMap<>();
        for (OrderUpdate update : repositorioNovedad.findByPeriodId(period.getId())) {
            boolean changesRate = update.getUpdateType() == OrderUpdateType.CAMBIO_PLAN
                    || update.getUpdateType() == OrderUpdateType.CAMBIO_SERVICIO;
            if (!changesRate || update.getEffectiveDate() == null
                    || !update.getEffectiveDate().isAfter(period.getStartDate())) {
                continue;
            }
            first.merge(update.getSiteId(), update,
                    (a, b) -> b.getEffectiveDate().isBefore(a.getEffectiveDate()) ? b : a);
        }
        Map<UUID, Money> rates = new HashMap<>();
        first.forEach((siteId, update) -> {
            if (update.getPreviousValue() != null && update.getPreviousValue().isPositive()) {
                rates.put(siteId, update.getPreviousValue());
            }
        });
        return rates;
    }

    /**
     * De menor a mayor prioridad: la factura del periodo anterior (su cargo fijo), lo que esta
     * misma factura ya cobró al recalcularla, y la tarifa con que cerró el periodo anterior
     * según sus novedades. Esta última manda sobre lo ya cobrado: si una novedad del mes
     * anterior llega cuando esta factura ya existe, el plan nuevo rige desde su día 1.
     */
    private Map<UUID, Money> resolveOpeningRatesFromInvoices(UUID clientId, BillingPeriod period, Invoice previousVersion) {
        Optional<BillingPeriod> previousPeriod = previousPeriodCode(period.getCode()).flatMap(repositorioPeriodo::findByCode);
        Map<UUID, Money> rates = new HashMap<>();
        previousPeriod.flatMap(prev -> repositorioFactura.findCurrentByClientAndPeriod(clientId, prev.getId()))
                .map(this::extractRatesBySite)
                .ifPresent(rates::putAll);
        if (previousVersion != null) {
            rates.putAll(extractRatesBySite(previousVersion));
        }
        previousPeriod.map(this::closingRatesFromUpdates).ifPresent(rates::putAll);
        return rates;
    }

    /**
     * Tarifa por sede al INICIO del periodo de la factura: la del cargo fijo (concepto sin
     * novedad de origen). Las líneas de incremento traen la tarifa posterior al cambio y no
     * sirven aquí. Si la sede solo tiene la línea de un alta, se toma esa.
     */
    private Map<UUID, Money> extractRatesBySite(Invoice invoice) {
        Map<UUID, Money> rates = new HashMap<>();
        Map<UUID, Money> fromNovelties = new HashMap<>();
        for (InvoiceItem item : invoice.getItems()) {
            if (item.getSiteId() == null || item.isInstallationFeeLine()) {
                continue;
            }
            Money rate = item.getBaseMonthlyRate();
            if (rate == null || !rate.isPositive()) {
                continue; // línea anterior a la migración V10: sin tarifa base registrada
            }
            if (item.getSourceUpdateId() == null) {
                rates.putIfAbsent(item.getSiteId(), rate);
            } else {
                fromNovelties.putIfAbsent(item.getSiteId(), rate);
            }
        }
        fromNovelties.forEach(rates::putIfAbsent);
        return rates;
    }

    /**
     * Tarifa con que cada sede TERMINÓ un periodo: la de su última novedad que fija tarifa
     * (alta, reactivación o cambio de plan/servicio). Manda sobre la del cargo fijo de esa
     * factura, que es la del día 1 de aquel mes, y cubre también las bajadas de plan, que
     * no dejan una línea propia.
     */
    private Map<UUID, Money> closingRatesFromUpdates(BillingPeriod period) {
        Map<UUID, OrderUpdate> latest = new HashMap<>();
        for (OrderUpdate update : repositorioNovedad.findByPeriodId(period.getId())) {
            OrderUpdateType type = update.getUpdateType();
            boolean setsRate = type == OrderUpdateType.ALTA || type == OrderUpdateType.REACTIVACION
                    || type == OrderUpdateType.CAMBIO_PLAN || type == OrderUpdateType.CAMBIO_SERVICIO;
            if (!setsRate || update.getValue() == null || !update.getValue().isPositive()
                    || update.getEffectiveDate() == null || update.getEffectiveDate().isAfter(period.getEndDate())) {
                continue;
            }
            latest.merge(update.getSiteId(), update,
                    (a, b) -> b.getEffectiveDate().isAfter(a.getEffectiveDate()) ? b : a);
        }
        Map<UUID, Money> rates = new HashMap<>();
        latest.forEach((siteId, update) -> rates.put(siteId, update.getValue()));
        return rates;
    }

    /** "2026-09" -&gt; "2026-08". Vacío si el código no tiene el formato YYYY-MM esperado. */
    private Optional<String> previousPeriodCode(String periodCode) {
        if (periodCode == null || !periodCode.matches("\\d{4}-\\d{2}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(java.time.YearMonth.parse(periodCode).minusMonths(1).toString());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private List<InstallationFee> loadBillableFees(List<SubscriptionSite> sites) {
        List<InstallationFee> fees = new ArrayList<>();
        for (SubscriptionSite site : sites) {
            fees.addAll(repositorioCuotaInstalacion.findBySiteIdAndStatus(site.getId(), InstallationFeeStatus.PENDING));
            fees.addAll(repositorioCuotaInstalacion.findBySiteIdAndStatus(site.getId(), InstallationFeeStatus.BILLING));
        }
        return fees;
    }

    /** Los campos extra (EAV) son datos de presentación del cliente: se heredan entre versiones. */
    private void carryOverExtraFields(Invoice previous, Invoice newVersion) {
        previous.getExtraFields().forEach(ef -> {
            try {
                newVersion.addExtraField(ef.getDefinition(), ef.getValue());
            } catch (RuntimeException ex) {
                log.warn("No se pudo heredar el campo extra '{}' a la nueva versión: {}",
                        ef.getDefinition() != null ? ef.getDefinition().getFieldKey() : "?", ex.getMessage());
            }
        });
    }

    private void archivePreviousPdf(Invoice previous, String reason, String user) {
        if (repositorioHistoricoPdf == null) {
            return;
        }
        InvoicePdfHistory snapshot = InvoicePdfHistory.snapshotOf(
                previous, InvoicePdfHistory.ORIGIN_REGENERATION, reason, user);
        if (snapshot != null) {
            repositorioHistoricoPdf.save(snapshot);
            log.info("PDF oficial de la factura {} archivado en histórico antes de regenerar", previous.getInvoiceNumber());
        }
    }

    private void markUpdatesAsBilled(List<OrderUpdate> updates, UUID invoiceId) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        updates.forEach(u -> u.markAsBilled(invoiceId));
        repositorioNovedad.saveAll(updates);
    }

    private String nextInvoiceNumber(BillingType billingType) {
        String prefix = (billingType == BillingType.F20) ? "FEMG" : "FESP";
        return prefix + " " + (INVOICE_NUMBER_BASE + repositorioFactura.count() + 1);
    }

    @Override
    @Transactional(readOnly = true)
    public String renderInvoiceHtml(UUID invoiceId) {
        Invoice invoice = getInvoiceById(invoiceId);
        return graphicRenderer.renderHtml(invoice, chargeBreakdown(invoice));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] renderInvoicePdf(UUID invoiceId) {
        Invoice invoice = getInvoiceById(invoiceId);
        return graphicRenderer.renderPdf(invoice, chargeBreakdown(invoice));
    }

    /**
     * Detalle por bloques para la representación gráfica, para facturas internas y de Siigo.
     * Cuando Siigo envió su PDF oficial se entrega ese archivo tal cual y esto no aplica.
     */
    private com.sistemaspalacios.billing.service.dto.ChargeBreakdownDto chargeBreakdown(Invoice invoice) {
        if (chargeBreakdownService == null) {
            return null;
        }
        return chargeBreakdownService.breakdown(invoice);
    }

    @Autowired(required = false)
    private InvoiceChargeBreakdownService chargeBreakdownService;

    @Autowired(required = false)
    private com.sistemaspalacios.billing.service.siigo.PdfQrCodeExtractor pdfQrCodeExtractor;

    @Override
    @Transactional
    public byte[] getInvoiceQrImage(String reference) {
        if (reference == null || reference.isBlank()) return null;
        Invoice invoice = resolveInvoiceByReference(reference.trim());
        if (invoice == null) return null;

        // 1. Si existe imagen física en disco
        if (invoice.getQrImagePath() != null && !invoice.getQrImagePath().isBlank()) {
            java.io.File imgFile = new java.io.File(invoice.getQrImagePath());
            if (imgFile.exists() && imgFile.isFile()) {
                try {
                    return java.nio.file.Files.readAllBytes(imgFile.toPath());
                } catch (Exception ex) {
                    log.warn("Error leyendo imagen de QR en {}: {}", invoice.getQrImagePath(), ex.getMessage());
                }
            }
        }

        // 2. Si tiene contenido de QR pero no el archivo en disco, generar con ZXing
        if (invoice.getQrContent() != null && !invoice.getQrContent().isBlank()) {
            var extractor = pdfQrCodeExtractor != null ? pdfQrCodeExtractor : new com.sistemaspalacios.billing.service.siigo.PdfQrCodeExtractor();
            byte[] generated = extractor.generateQrPng(invoice.getQrContent());
            if (generated != null) return generated;
        }

        // 3. Si no tiene qrContent pero tiene PDF, extraer en caliente
        if (invoice.getPdfPath() != null && !invoice.getPdfPath().isBlank()) {
            java.io.File pdfFile = new java.io.File(invoice.getPdfPath());
            if (pdfFile.exists() && pdfFile.isFile()) {
                var extractor = pdfQrCodeExtractor != null ? pdfQrCodeExtractor : new com.sistemaspalacios.billing.service.siigo.PdfQrCodeExtractor();
                var extracted = extractor.extractQr(pdfFile);
                if (extracted.hasContent()) {
                    invoice.setQrContent(extracted.content());
                    if (extracted.imageBytes() != null) {
                        try {
                            java.nio.file.Path parent = pdfFile.toPath().getParent();
                            if (parent != null) {
                                java.nio.file.Path qrPath = parent.resolve("qr.png");
                                java.nio.file.Files.write(qrPath, extracted.imageBytes());
                                invoice.setQrImagePath(qrPath.toString());
                            }
                        } catch (Exception ignored) {}
                    }
                    repositorioFactura.save(invoice);
                    return extracted.imageBytes() != null ? extracted.imageBytes() : extractor.generateQrPng(extracted.content());
                }
            }
        }

        return null;
    }

    private Invoice resolveInvoiceByReference(String ref) {
        try {
            UUID id = UUID.fromString(ref);
            return repositorioFactura.findById(id).orElse(null);
        } catch (IllegalArgumentException ignored) {}

        var matcher = java.util.regex.Pattern.compile("^(F1|F20|FEMG|FESP)[-_:]?(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(ref);
        if (matcher.matches()) {
            String prefix = matcher.group(1).toUpperCase(java.util.Locale.ROOT);
            String number = matcher.group(2).replaceFirst("^0+(?!$)", "");
            BillingType type = (prefix.contains("20") || prefix.contains("FEMG")) ? BillingType.F20 : BillingType.F1;
            var byKey = repositorioFactura.findByExternalDocumentKey(type.name() + ":" + number);
            if (byKey.isPresent()) return byKey.get();

            var candidates = repositorioFactura.findCandidatesByBillingTypeAndConsecutive(type, number);
            if (!candidates.isEmpty()) return candidates.get(0);
        }

        var numMatcher = java.util.regex.Pattern.compile("(\\d+)$").matcher(ref);
        if (numMatcher.find()) {
            String number = numMatcher.group(1).replaceFirst("^0+(?!$)", "");
            var list = repositorioFactura.findAll().stream()
                    .filter(i -> i.getInvoiceNumber() != null && i.getInvoiceNumber().replaceFirst("^0+(?!$)", "").endsWith(number))
                    .toList();
            if (!list.isEmpty()) return list.get(0);
        }

        return null;
    }
}
