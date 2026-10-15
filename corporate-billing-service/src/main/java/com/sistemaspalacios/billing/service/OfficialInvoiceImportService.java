package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.*;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.*;
import com.sistemaspalacios.billing.service.dto.OfficialInvoiceData;
import com.sistemaspalacios.billing.service.siigo.OfficialInvoiceReader;
import com.sistemaspalacios.billing.service.siigo.SiigoSharedFolderReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.time.YearMonth;
import java.util.*;

@Service
public class OfficialInvoiceImportService {
    private final OfficialInvoiceReader reader;
    private final SiigoSharedFolderReader folder;
    private final RepositorioFactura invoices;
    private final RepositorioCliente clients;
    private final RepositorioPeriodo periods;
    private final BillingIncidentService incidentService;

    @org.springframework.beans.factory.annotation.Autowired
    public OfficialInvoiceImportService(
            OfficialInvoiceReader reader,
            SiigoSharedFolderReader folder,
            @org.springframework.beans.factory.annotation.Qualifier("repositorioFactura") RepositorioFactura invoices,
            RepositorioCliente clients,
            RepositorioPeriodo periods,
            @org.springframework.beans.factory.annotation.Autowired(required = false) BillingIncidentService incidentService) {
        this.reader = reader;
        this.folder = folder;
        this.invoices = invoices;
        this.clients = clients;
        this.periods = periods;
        this.incidentService = incidentService;
    }

    public OfficialInvoiceImportService(
            OfficialInvoiceReader reader,
            SiigoSharedFolderReader folder,
            RepositorioFactura invoices,
            RepositorioCliente clients,
            RepositorioPeriodo periods) {
        this(reader, folder, invoices, clients, periods, null);
    }

    @Transactional
    public List<UUID> importFile(String name, InputStream stream) { return importDetailed(name, stream).invoiceIds(); }

    @Transactional
    public ImportResult importDetailed(String name, InputStream stream) {
        List<UUID> result = new ArrayList<>();
        int created = 0, repeated = 0;
        for (OfficialInvoiceData data : reader.read(name, stream)) {
            boolean exists = data.billingType() != null && data.invoiceNumber() != null
                    && invoices.findByExternalDocumentKey(data.billingType().name() + ":" + data.invoiceNumber().replaceFirst("^0+(?!$)", "")).isPresent();
            result.add(importDocument(data).getId());
            if (exists) repeated++; else created++;
        }
        return new ImportResult(List.copyOf(result), created, repeated);
    }
    public record ImportResult(List<UUID> invoiceIds, int created, int repeated) {}

    private boolean sameItems(Invoice invoice, OfficialInvoiceData data) {
        var stored = invoice.getItems().stream().map(i -> Arrays.asList(i.getProductCode(), i.getDescription(),
                i.getQuantity().stripTrailingZeros().toPlainString(), i.getTaxableBase().toString(), i.getIvaValue().toString()).toString()).sorted().toList();
        var incoming = data.items().stream().map(i -> Arrays.asList(i.code(), i.description(),
                i.quantity().stripTrailingZeros().toPlainString(), i.subtotal().toString(), i.iva().toString()).toString()).sorted().toList();
        return stored.equals(incoming);
    }

    @Transactional
    public Invoice importDocument(OfficialInvoiceData data) {
        return importDocument(data, java.time.LocalDate.now(), java.time.Instant.now());
    }

    @Transactional
    public Invoice importDocument(OfficialInvoiceData data, java.time.LocalDate importDate, java.time.Instant availableAt) {
        if (data.nit() == null || data.nit().isBlank() || data.billingType() == null || data.issueDate() == null
                || data.invoiceNumber() == null || !data.invoiceNumber().matches("[0-9]+")
                || data.items() == null || data.items().isEmpty() || data.total() == null || !data.total().isPositive()) {
            throw new IllegalArgumentException("Factura oficial incompleta: requiere NIT, tipo, consecutivo numérico, fecha, total e ítems");
        }
        Money total = Money.ZERO;
        for (var item : data.items()) {
            if (item.description() == null || item.description().isBlank() || item.quantity() == null || item.quantity().signum() <= 0
                    || item.subtotal() == null || item.iva() == null || item.subtotal().isNegative() || item.iva().isNegative()) {
                throw new IllegalArgumentException("Ítem oficial inválido");
            }
            total = total.plus(item.subtotal()).plus(item.iva());
        }
        if (!total.equals(data.total())) throw new IllegalArgumentException("El detalle no cuadra con el total de la factura " + data.invoiceNumber());
        String number = data.invoiceNumber().replaceFirst("^0+(?!$)", "");
        String key = data.billingType().name() + ":" + number;
        Optional<Invoice> existing = invoices.findByExternalDocumentKey(key);
        if (existing.isPresent()) {
            Invoice invoice = existing.get();
            // Una fila del corte de cartera trae el SALDO, no el detalle: cambia con cada abono y no
            // modifica el documento (su detalle pudo llegar después desde el PDF). El saldo lo
            // concilia la sincronización de cartera; aquí solo se reconoce el mismo documento.
            if (isCarteraRow(data) && invoice.getClientNit().equals(data.nit().trim())
                    && invoice.getIssueDate().equals(data.issueDate())) {
                return invoice;
            }
            if (!invoice.getClientNit().equals(data.nit().trim()) || !invoice.getTotalAmount().equals(total)
                    || !invoice.getIssueDate().equals(data.issueDate()) || !sameItems(invoice, data)) {
                throw new IllegalArgumentException("Documento oficial modificado: " + key + ". Requiere conciliación, no sobrescritura");
            }
            if (data.cufe() != null && !data.cufe().isBlank()) {
                if (invoice.getCufe() != null && !invoice.getCufe().equals(data.cufe()))
                    throw new IllegalArgumentException("CUFE contradictorio para " + key);
                invoice.setCufe(data.cufe());
            }
            return invoice;
        }
        CorporateClient client = clients.findByNit(data.nit().trim()).orElseGet(() -> clients.saveAndFlush(new CorporateClient(
                UUID.randomUUID(), data.nit().trim(), data.businessName() != null && !data.businessName().isBlank()
                ? data.businessName() : "NIT " + data.nit().trim() + " (razón social pendiente)",
                null, null, null, (short) (data.billingType() == BillingType.F20 ? 2 : 1), (short) 1,
                data.billingType(), false, null, null)));
        YearMonth month = YearMonth.from(data.issueDate());
        BillingPeriod period = periods.findByCode(month.toString()).orElseGet(() -> periods.saveAndFlush(
                new BillingPeriod(UUID.randomUUID(), month.toString(), BillingCutType.ADVANCE, month.atDay(1), month.atEndOfMonth(), true)));
        Invoice invoice = new Invoice(UUID.randomUUID(), client.getId(), period.getId(), data.billingType(), client, data.issueDate(), data.dueDate());
        // La ausencia de vencimiento no inventa un plazo: se completa desde cartera.
        invoice.setDueDate(data.dueDate());
        invoice.setInvoiceNumber(number);
        invoice.setCufe(data.cufe());
        invoice.markImportedFromSiigo(key);
        for (var item : data.items()) invoice.addItem(InvoiceItem.fromOfficialAmounts(invoice.getId(), item.code(), item.description(), item.quantity(), item.subtotal(), item.iva()));
        // Periodo del servicio ("GR 01-31 AGT 2026"), no el mes de emisión, cuando el detalle lo dice.
        YearMonth resolvedServiceMonth = OfficialInvoiceDetailService.servicePeriodOf(invoice.getItems()).orElse(month);
        if (!resolvedServiceMonth.equals(month)) {
            invoice.reassignServicePeriod(periods.findByCode(resolvedServiceMonth.toString()).orElseGet(() -> periods.saveAndFlush(
                    new BillingPeriod(UUID.randomUUID(), resolvedServiceMonth.toString(), BillingCutType.ADVANCE, resolvedServiceMonth.atDay(1), resolvedServiceMonth.atEndOfMonth(), true))).getId());
        }

        java.time.LocalDate effectiveImportDate = importDate != null ? importDate : java.time.LocalDate.now();
        java.time.Instant effectiveAvailableAt = availableAt != null ? availableAt : java.time.Instant.now();
        invoice.markAvailableForPayment(effectiveAvailableAt);

        // Control de disponibilidad y retraso según tipo de regla
        java.time.LocalDate cutoffDate = null;
        if (client.getBillingRule() == ClientBillingRule.ORDINARIA) {
            cutoffDate = resolvedServiceMonth.atDay(2);
        } else if (client.getBillingRule() == ClientBillingRule.ESPECIAL_CORPORATIVA && client.getCorporateCutoffDay() != null) {
            cutoffDate = resolvedServiceMonth.atDay(client.getCorporateCutoffDay());
        }

        if (cutoffDate != null && effectiveImportDate.isAfter(cutoffDate)) {
            invoice.setImportDelayRecorded(true);
            org.slf4j.LoggerFactory.getLogger(OfficialInvoiceImportService.class).warn(
                    "Factura {} para cliente NIT {} correspondiente al periodo {} importada con retraso " +
                    "(fecha de importación: {}, posterior al corte: {}). " +
                    "El periodo de servicio oficial se conserva íntegramente.",
                    number, client.getNit(), resolvedServiceMonth, effectiveImportDate, cutoffDate);
        }

        invoice.consolidate();
        Invoice saved = invoices.saveAndFlush(invoice);

        // Resolución automática de incidencias de facturas faltantes si la factura llegó
        if (incidentService != null) {
            try {
                incidentService.autoResolveMissingInvoiceIncident(client.getId(), resolvedServiceMonth.toString(), saved.getInvoiceNumber());
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(OfficialInvoiceImportService.class).error(
                        "Error al auto-resolver incidencia para cliente NIT {} en periodo {}: {}",
                        client.getNit(), resolvedServiceMonth, e.getMessage());
            }
        }

        checkAndLogPreviousPortfolio(client, saved);
        return saved;
    }

    private void checkAndLogPreviousPortfolio(CorporateClient client, Invoice newInvoice) {
        List<Invoice> existingInvoices = invoices.findByClientId(client.getId());
        Money priorDebt = Money.ZERO;
        int pendingCount = 0;
        for (Invoice inv : existingInvoices) {
            if (!inv.getId().equals(newInvoice.getId()) && inv.isCurrentVersion()
                    && inv.getStatus() != InvoiceStatus.VOIDED
                    && inv.getStatus() != InvoiceStatus.SUPERSEDED
                    && inv.getPendingBalance().isPositive()) {
                priorDebt = priorDebt.plus(inv.getPendingBalance());
                pendingCount++;
            }
        }
        if (priorDebt.isPositive()) {
            org.slf4j.LoggerFactory.getLogger(OfficialInvoiceImportService.class).info(
                    "Cliente NIT {} tiene {} factura(s) anterior(es) con saldo pendiente por {}. "
                    + "Nueva factura {} por {} registrada como obligación independiente. Saldo total por cobrar: {}",
                    client.getNit(), pendingCount, priorDebt, newInvoice.getInvoiceNumber(),
                    newInvoice.getTotalAmount(), priorDebt.plus(newInvoice.getTotalAmount()));
        }
    }

    /** Documento leído del corte de cartera: un solo concepto genérico con el saldo de la fila. */
    private static boolean isCarteraRow(OfficialInvoiceData data) {
        return data.items().size() == 1
                && Invoice.CARTERA_PLACEHOLDER_CODE.equals(data.items().get(0).code())
                && Invoice.CARTERA_PLACEHOLDER_DESCRIPTION.equals(data.items().get(0).description());
    }
}
