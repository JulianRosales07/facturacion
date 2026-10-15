package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.WithholdingDetail;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate Root: Consolidated Corporate Invoice (factura).
 * Invariants:
 * - One active invoice per client + period (enforced by DB partial unique index).
 * - Client fiscal data captured as immutable snapshot.
 * - Header totals strictly balance with items sum.
 * - Withholdings do NOT affect official total (subtotal + IVA).
 * - Cannot be issued without items.
 */
@Entity
@Table(name = "factura")
public class Invoice {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "cliente_corporativo_id", nullable = false)
    private UUID clientId;

    @Column(name = "periodo_facturacion_id", nullable = false)
    private UUID periodId;

    @Column(name = "lote_exportacion_id")
    private UUID exportBatchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_facturacion", nullable = false)
    private BillingType billingType;

    @Column(name = "numero_factura", length = 30)
    private String invoiceNumber;

    @Column(name = "cufe", length = 96)
    private String cufe;

    @Column(name = "estado_envio", length = 40)
    private String dispatchStatus;

    @Column(name = "ruta_pdf", length = 500)
    private String pdfPath;

    @Column(name = "qr_contenido", columnDefinition = "TEXT")
    private String qrContent;

    @Column(name = "qr_imagen_path", length = 500)
    private String qrImagePath;

    @Column(name = "origen_documento", nullable = false)
    private String documentOrigin = "INTERNO";

    @Column(name = "clave_documento_externo")
    private String externalDocumentKey;

    public boolean isExternalDocument() { return "SIIGO".equals(documentOrigin); }
    /** SIIGO (documento oficial recibido) o INTERNO (generado en este sistema). */
    public String getDocumentOrigin() { return documentOrigin; }
    public void markImportedFromSiigo(String key) {
        documentOrigin = "SIIGO";
        externalDocumentKey = Objects.requireNonNull(key);
    }

    /** Código y descripción del concepto único que crea la importación del corte de cartera. */
    public static final String CARTERA_PLACEHOLDER_CODE = "SERV-CORP";
    public static final String CARTERA_PLACEHOLDER_DESCRIPTION = "Facturación de servicios corporativos";

    /**
     * Documento de Siigo que solo tiene el concepto genérico del corte de cartera: su detalle
     * real (conceptos, base e IVA) todavía no se conoce.
     */
    public boolean hasCarteraPlaceholderDetail() {
        return isExternalDocument() && items.size() == 1
                && CARTERA_PLACEHOLDER_CODE.equals(items.get(0).getProductCode())
                && CARTERA_PLACEHOLDER_DESCRIPTION.equals(items.get(0).getDescription());
    }

    /** La cartera informa deuda; no acredita el detalle tributario necesario para cobrar. */
    public boolean isPayable() {
        return isCurrentVersion() && status == InvoiceStatus.CONSOLIDATED
                && !hasCarteraPlaceholderDetail() && !items.isEmpty()
                && totalAmount.isPositive() && getPendingBalance().isPositive()
                && getPendingBalance().compareTo(totalAmount) <= 0;
    }

    public void initializeReceivable(Money balance, Instant cutoffAt) {
        if (!hasCarteraPlaceholderDetail() || status != InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Solo una obligación de cartera nueva puede inicializarse sin detalle");
        }
        changeStatus(InvoiceStatus.CONSOLIDATED);
        applySiigoBalance(balance, cutoffAt, null);
    }

    /**
     * Reemplaza el concepto genérico del corte de cartera por el detalle oficial que Siigo
     * imprimió en la factura. Solo para documentos de Siigo que aún tienen el genérico: un
     * detalle oficial ya cargado nunca se sobrescribe (una diferencia es un conflicto).
     */
    public void applyOfficialDetail(List<InvoiceItem> officialItems) {
        if (!hasCarteraPlaceholderDetail()) {
            throw new IllegalStateException("La factura " + invoiceNumber + " ya tiene detalle oficial; no se sobrescribe");
        }
        if (officialItems == null || officialItems.isEmpty()) {
            throw new IllegalArgumentException("El detalle oficial no puede estar vacío");
        }
        this.items.clear();
        for (InvoiceItem item : officialItems) {
            item.setInvoiceId(this.id);
            this.items.add(item);
        }
        recalculateTotals();
    }

    /** Periodo del servicio facturado (no el del mes de emisión), para documentos de Siigo. */
    public void reassignServicePeriod(UUID servicePeriodId) {
        if (!isExternalDocument()) {
            throw new IllegalStateException("Solo el periodo de un documento de Siigo se toma de su detalle");
        }
        this.periodId = Objects.requireNonNull(servicePeriodId, "servicePeriodId cannot be null");
    }
    public String getExternalDocumentKey() { return externalDocumentKey; }
    public void setExternalDocumentKey(String externalDocumentKey) { this.externalDocumentKey = externalDocumentKey; }

    // Immutable fiscal snapshot (HU-09)
    @Column(name = "nit_cliente", nullable = false, length = 20)
    private String clientNit;

    @Column(name = "razon_social_cliente", nullable = false, length = 255)
    private String clientBusinessName;

    @Column(name = "direccion_fiscal_cliente", length = 255)
    private String clientTaxAddress;

    @Column(name = "email_facturacion_cliente", length = 150)
    private String clientBillingEmail;

    // Official financial totals
    @Column(name = "valor_bruto", nullable = false, precision = 14, scale = 2)
    private Money grossAmount = Money.ZERO;

    @Column(name = "valor_descuento", nullable = false, precision = 14, scale = 2)
    private Money discountAmount = Money.ZERO;

    @Column(name = "valor_subtotal", nullable = false, precision = 14, scale = 2)
    private Money subtotalAmount = Money.ZERO;

    @Column(name = "valor_iva", nullable = false, precision = 14, scale = 2)
    private Money ivaAmount = Money.ZERO;

    @Column(name = "valor_total", nullable = false, precision = 14, scale = 2)
    private Money totalAmount = Money.ZERO; // OFFICIAL INVOICE VALUE = subtotal + IVA

    // Withholdings for payment settlement only (does NOT alter official value nor accounting entry)
    @Column(name = "valor_retefuente", nullable = false, precision = 14, scale = 2)
    private Money retefuenteAmount = Money.ZERO;

    @Column(name = "valor_reteica", nullable = false, precision = 14, scale = 2)
    private Money reteicaAmount = Money.ZERO;

    @Column(name = "valor_reteiva", nullable = false, precision = 14, scale = 2)
    private Money reteivaAmount = Money.ZERO;

    @Column(name = "valor_estampillas", nullable = false, precision = 14, scale = 2)
    private Money estampillasAmount = Money.ZERO;

    @Column(name = "valor_total_retenciones", nullable = false, precision = 14, scale = 2)
    private Money totalWithholdings = Money.ZERO;

    @Column(name = "neto_a_pagar", nullable = false, precision = 14, scale = 2)
    private Money netPayableAmount = Money.ZERO; // totalAmount - totalWithholdings

    @Column(name = "fecha_emision", nullable = false)
    private LocalDate issueDate;

    @Column(name = "fecha_vencimiento")
    private LocalDate dueDate;

    @Column(name = "forma_pago", length = 20)
    private String paymentTerm = "CREDITO";

    @Column(name = "estado", nullable = false)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    /**
     * Eventos de dominio pendientes (cambio de estado, PDF recibido). Spring Data los publica al
     * guardar la factura con su repositorio; el aviso a los frontends se envía tras el commit.
     */
    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    @org.springframework.data.domain.DomainEvents
    Collection<Object> domainEvents() {
        return List.copyOf(domainEvents);
    }

    @org.springframework.data.domain.AfterDomainEventPublication
    void clearDomainEvents() {
        domainEvents.clear();
    }

    private void changeStatus(InvoiceStatus next, UUID paymentId, String transactionId) {
        InvoiceStatus previous = this.status;
        this.status = next;
        if (previous != null && previous != next) {
            domainEvents.add(new com.sistemaspalacios.billing.domain.event.InvoiceStatusChanged(
                    id, clientNit, billingType, invoiceNumber, previous, next, paymentId, transactionId));
        }
    }

    private void changeStatus(InvoiceStatus next) {
        changeStatus(next, null, null);
    }

    @Column(name = "anulado_en")
    private Instant voidedAt;

    @Column(name = "motivo_anulacion", length = 255)
    private String voidReason;

    @Column(name = "creado_por", length = 100)
    private String createdBy;

    @Column(name = "anulado_por", length = 100)
    private String voidedBy;

    @Column(name = "es_residencial", nullable = false)
    private boolean residential = false;

    // ---------------------------------------------------------------------------
    // Versionado / refacturación (Sprint 10)
    // ---------------------------------------------------------------------------

    /** Versión dentro de la cadena de refacturación. La primera emisión es 1. */
    @Column(name = "numero_version", nullable = false)
    private int versionNumber = 1;

    /** Raíz de la cadena. En la versión 1 apunta a sí misma. */
    @Column(name = "factura_origen_id", nullable = false)
    private UUID originInvoiceId;

    /** Versión inmediatamente anterior. NULL en la versión 1. */
    @Column(name = "factura_anterior_id")
    private UUID previousInvoiceId;

    /** Versión que sustituyó a esta factura. NULL mientras sea la vigente. */
    @Column(name = "reemplazada_por_id")
    private UUID supersededById;

    @Column(name = "reemplazada_en")
    private Instant supersededAt;

    @Column(name = "motivo_regeneracion", length = 255)
    private String regenerationReason;

    @Column(name = "regenerado_por", length = 100)
    private String regeneratedBy;

    // ---------------------------------------------------------------------------
    // Saldo autoritativo de Siigo (Sprint 13)
    // ---------------------------------------------------------------------------

    /**
     * Saldo pendiente según el corte de cartera de Siigo. **Es la fuente de verdad.**
     * <p>
     * {@code null} significa que nunca se ha recibido un corte para esta factura; en ese
     * caso rige el saldo calculado localmente ({@link #getComputedPendingBalance()}).
     */
    @Column(name = "saldo_siigo", precision = 14, scale = 2)
    private Money siigoBalance;

    @Column(name = "cruce_local_pendiente_siigo", nullable = false, precision = 14, scale = 2)
    private Money unreconciledLocalSettlement = Money.ZERO;

    @Column(name = "ultimo_cruce_local_en")
    private Instant lastLocalSettlementAt;

    @Column(name = "saldo_siigo_actualizado_en")
    private Instant siigoBalanceUpdatedAt;

    /** Fecha de generación del corte que produjo el saldo, no de su ingesta. */
    @Column(name = "saldo_siigo_corte_en")
    private Instant siigoBalanceCutoffAt;

    @Column(name = "saldo_siigo_snapshot_id")
    private UUID siigoBalanceSnapshotId;

    @Transient
    private Money creditNoteAmount = Money.ZERO;

    @Transient
    private Money paidAmount = Money.ZERO;

    @Column(name = "disponible_para_pago_en")
    private Instant availableForPaymentAt;

    @Column(name = "retraso_importacion_registrado", nullable = false)
    private boolean importDelayRecorded = false;

    @Transient
    private LocalDate paidDate;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en", insertable = false, updatable = false)
    private Instant updatedAt;

    // factura_id lo escribe InvoiceItem.invoiceId. Sin insertable/updatable = false, al
    // reemplazar los conceptos de una factura en preparación Hibernate intenta dejar
    // factura_id en NULL antes de borrar los ítems huérfanos, y la columna no lo admite.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "factura_id", nullable = false, insertable = false, updatable = false)
    private List<InvoiceItem> items = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "factura_id")
    private List<InvoiceExtraField> extraFields = new ArrayList<>();

    @Transient
    private final List<WithholdingDetail> withholdingDetails = new ArrayList<>();

    protected Invoice() {
        // JPA
    }

    public Invoice(
            UUID id,
            UUID clientId,
            UUID periodId,
            BillingType billingType,
            CorporateClient clientSnapshot,
            LocalDate issueDate,
            LocalDate dueDate
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.clientId = Objects.requireNonNull(clientId, "clientId cannot be null");
        this.periodId = Objects.requireNonNull(periodId, "periodId cannot be null");
        this.billingType = billingType != null ? billingType : BillingType.F1;
        this.issueDate = issueDate != null ? issueDate : LocalDate.now();
        this.dueDate = dueDate != null ? dueDate : this.issueDate.plusDays(30);
        this.originInvoiceId = this.id; // raíz de su propia cadena hasta que se encadene

        if (clientSnapshot != null) {
            this.clientNit = clientSnapshot.getNit();
            this.clientBusinessName = clientSnapshot.getBusinessName();
            this.clientTaxAddress = clientSnapshot.getTaxAddress();
            this.clientBillingEmail = clientSnapshot.getBillingEmail();
        }
    }

    public Invoice(
            UUID id,
            UUID clientId,
            UUID periodId,
            String invoiceNumber,
            LocalDate issueDate,
            LocalDate dueDate,
            BillingType billingType,
            InvoiceStatus status
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.clientId = clientId;
        this.periodId = periodId;
        this.invoiceNumber = invoiceNumber;
        this.issueDate = issueDate != null ? issueDate : LocalDate.now();
        this.dueDate = dueDate != null ? dueDate : this.issueDate.plusDays(30);
        this.billingType = billingType != null ? billingType : BillingType.F1;
        this.status = status != null ? status : InvoiceStatus.DRAFT;
        this.originInvoiceId = this.id;
    }

    public void addItem(InvoiceItem item) {
        Objects.requireNonNull(item, "item cannot be null");
        item.setInvoiceId(this.id);
        this.items.add(item);
        recalculateTotals();
    }

    public void addItems(List<InvoiceItem> newItems) {
        if (newItems != null) {
            for (InvoiceItem item : newItems) {
                item.setInvoiceId(this.id);
                this.items.add(item);
            }
            recalculateTotals();
        }
    }

    public void setWithholdings(List<WithholdingDetail> details) {
        this.withholdingDetails.clear();
        this.retefuenteAmount = Money.ZERO;
        this.reteicaAmount = Money.ZERO;
        this.reteivaAmount = Money.ZERO;
        this.estampillasAmount = Money.ZERO;

        if (details != null) {
            this.withholdingDetails.addAll(details);
            for (WithholdingDetail detail : details) {
                switch (detail.conceptCode()) {
                    case RETEFUENTE -> this.retefuenteAmount = this.retefuenteAmount.plus(detail.withheldAmount());
                    case RETEICA -> this.reteicaAmount = this.reteicaAmount.plus(detail.withheldAmount());
                    case RETEIVA -> this.reteivaAmount = this.reteivaAmount.plus(detail.withheldAmount());
                    case ESTAMPILLA -> this.estampillasAmount = this.estampillasAmount.plus(detail.withheldAmount());
                }
            }
        }

        this.totalWithholdings = retefuenteAmount.plus(reteicaAmount).plus(reteivaAmount).plus(estampillasAmount);
        this.netPayableAmount = totalAmount.minus(totalWithholdings);
    }

    public void recalculateTotals() {
        Money runningGross = Money.ZERO;
        Money runningDiscount = Money.ZERO;
        Money runningSubtotal = Money.ZERO;
        Money runningIva = Money.ZERO;

        for (InvoiceItem item : items) {
            if (!isExternalDocument()) item.recalculate();
            Money rawGross = isExternalDocument() ? item.getTaxableBase().plus(item.getDiscountValue())
                    : item.getUnitPrice().multiply(item.getQuantity());
            runningGross = runningGross.plus(rawGross);
            runningDiscount = runningDiscount.plus(item.getDiscountValue());
            runningSubtotal = runningSubtotal.plus(item.getTaxableBase());
            runningIva = runningIva.plus(item.getIvaValue());
        }

        this.grossAmount = runningGross;
        this.discountAmount = runningDiscount;
        this.subtotalAmount = runningSubtotal;
        this.ivaAmount = runningIva;
        this.totalAmount = subtotalAmount.plus(ivaAmount); // Official value
        this.netPayableAmount = totalAmount.minus(totalWithholdings);
    }

    public void consolidate() {
        assertCanBeIssued();
        changeStatus(InvoiceStatus.CONSOLIDATED);
    }

    // ---------------------------------------------------------------------------
    // Factura en preparación: acumula las novedades del periodo
    // ---------------------------------------------------------------------------

    /**
     * Motivo por el que esta factura ya no admite cambios en sus conceptos, o {@code null}
     * si sigue en preparación: interna, consolidada (o borrador), sin CUFE y sin lote de
     * exportación a Siigo. Los pagos y notas crédito se validan en el servicio, porque
     * viven en otros agregados.
     */
    public String editabilityBlocker() {
        if (isExternalDocument()) return "es un documento oficial de Siigo";
        if (status != InvoiceStatus.CONSOLIDATED && status != InvoiceStatus.DRAFT) {
            return "está en estado " + status.getDbValue();
        }
        if (cufe != null && !cufe.isBlank()) return "ya fue transmitida a la DIAN (tiene CUFE)";
        if (exportBatchId != null) return "ya está incluida en un lote de exportación a Siigo";
        return null;
    }

    /**
     * Sustituye los conceptos de la factura por los recalculados para el periodo,
     * conservando su identidad: mismo id, número, versión y estado. Los totales se
     * derivan de los conceptos nuevos, nunca se suman sobre los anteriores.
     */
    public void replaceItems(List<InvoiceItem> recalculatedItems) {
        String blocker = editabilityBlocker();
        if (blocker != null) {
            throw new IllegalStateException("La factura " + invoiceNumber + " no admite cambios: " + blocker);
        }
        Objects.requireNonNull(recalculatedItems, "recalculatedItems cannot be null");
        if (recalculatedItems.isEmpty()) {
            throw new IllegalStateException("La factura " + invoiceNumber
                    + " quedaría sin conceptos con las novedades del periodo; requiere revisión (anulación o ajuste)");
        }
        this.items.clear();
        for (InvoiceItem item : recalculatedItems) {
            item.setInvoiceId(this.id);
            this.items.add(item);
        }
        recalculateTotals();
        assertCanBeIssued();
    }

    // ---------------------------------------------------------------------------
    // Versionado / refacturación (Sprint 10)
    // ---------------------------------------------------------------------------

    /**
     * Encadena esta factura recién calculada como la versión siguiente de {@code previous},
     * heredando la raíz de la cadena y el snapshot fiscal ya congelado.
     * <p>
     * No modifica a {@code previous}: cerrar la versión anterior es responsabilidad de
     * {@link #supersede(UUID, String, String)}, que debe invocarse en la misma transacción.
     */
    public void markAsNextVersionOf(Invoice previous, String reason) {
        Objects.requireNonNull(previous, "previous invoice cannot be null");
        if (previous.getId().equals(this.id)) {
            throw new IllegalArgumentException("Una factura no puede ser la versión siguiente de sí misma");
        }
        if (!previous.getClientId().equals(this.clientId) || !previous.getPeriodId().equals(this.periodId)) {
            throw new IllegalArgumentException(
                    "Solo se puede versionar sobre una factura del mismo cliente y periodo");
        }
        this.originInvoiceId = previous.getOriginInvoiceId() != null
                ? previous.getOriginInvoiceId()
                : previous.getId();
        this.previousInvoiceId = previous.getId();
        this.versionNumber = previous.getVersionNumber() + 1;
        this.regenerationReason = reason;
    }

    /**
     * Cierra esta versión: deja de ser vigente y queda como histórico inmutable
     * apuntando a la versión que la sustituye.
     */
    public void supersede(UUID replacementInvoiceId, String reason, String user) {
        Objects.requireNonNull(replacementInvoiceId, "replacementInvoiceId cannot be null");
        if (replacementInvoiceId.equals(this.id)) {
            throw new IllegalArgumentException("Una factura no puede reemplazarse a sí misma");
        }
        assertCanBeRegenerated();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("El motivo de la regeneración es obligatorio");
        }
        changeStatus(InvoiceStatus.SUPERSEDED);
        this.supersededById = replacementInvoiceId;
        this.supersededAt = Instant.now();
        this.regenerationReason = reason;
        if (user != null && !user.isBlank()) {
            this.regeneratedBy = user;
        }
    }

    public boolean isSuperseded() {
        return this.status == InvoiceStatus.SUPERSEDED;
    }

    /** Vigente = todavía representa la obligación real del cliente para su periodo. */
    public boolean isCurrentVersion() {
        return this.status != null && this.status.isCurrent();
    }

    public boolean isFirstVersion() {
        return this.versionNumber <= 1;
    }

    /**
     * Una factura puede regenerarse mientras siga siendo vigente y no esté ya pagada.
     * Regenerar una factura pagada alteraría el valor de una obligación ya extinguida,
     * por lo que ese caso debe resolverse con nota crédito.
     */
    public boolean canBeRegenerated() {
        if (isExternalDocument()) return false;
        if (this.status == InvoiceStatus.VOIDED || this.status == InvoiceStatus.SUPERSEDED) {
            return false;
        }
        return this.status != InvoiceStatus.PAID;
    }

    public void assertCanBeRegenerated() {
        if (isExternalDocument()) throw new IllegalStateException("La factura oficial debe ajustarse en Siigo, no regenerarse localmente");
        if (this.status == InvoiceStatus.VOIDED) {
            throw new IllegalStateException(
                    "No se puede regenerar una factura anulada. Genere una factura nueva para el periodo.");
        }
        if (this.status == InvoiceStatus.SUPERSEDED) {
            throw new IllegalStateException(
                    "Esta factura ya fue reemplazada por la versión " + this.supersededById
                            + ". Regenere a partir de la versión vigente.");
        }
        if (this.status == InvoiceStatus.PAID) {
            throw new IllegalStateException(
                    "No se puede regenerar una factura ya pagada. El ajuste debe hacerse por nota crédito o débito.");
        }
    }

    public void voidInvoice(String reason) {
        voidInvoice(reason, null);
    }

    public void voidInvoice(String reason, String user) {
        if (isExternalDocument()) throw new IllegalStateException("La factura oficial debe anularse en Siigo y sincronizarse, no modificarse localmente");
        if (this.status == InvoiceStatus.VOIDED) {
            throw new IllegalStateException("La factura ya se encuentra anulada");
        }
        if (this.status == InvoiceStatus.SUPERSEDED) {
            throw new IllegalStateException(
                    "La factura ya fue reemplazada por una versión posterior y es histórico inmutable. "
                            + "Anule la versión vigente de la cadena.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Void reason must be specified");
        }
        changeStatus(InvoiceStatus.VOIDED);
        this.voidReason = reason;
        this.voidedAt = Instant.now();
        if (user != null && !user.isBlank()) {
            this.voidedBy = user;
        }
    }

    public boolean isVoided() {
        return this.status == InvoiceStatus.VOIDED;
    }

    public boolean canBeAdjusted() {
        return this.status != InvoiceStatus.VOIDED
                && this.status != InvoiceStatus.SUPERSEDED
                && this.status != InvoiceStatus.DRAFT;
    }

    public void assertCanBeIssued() {
        if (items.isEmpty()) {
            throw new IllegalStateException("An invoice cannot be issued without line items");
        }
        if (totalAmount.isZero() && grossAmount.isZero()) {
            throw new IllegalStateException("An invoice cannot be issued with zero total value");
        }
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public UUID getPeriodId() { return periodId; }
    public UUID getExportBatchId() { return exportBatchId; }
    public void setExportBatchId(UUID exportBatchId) { this.exportBatchId = exportBatchId; }
    public BillingType getBillingType() { return billingType; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public String getCufe() { return cufe; }
    public void setCufe(String cufe) { this.cufe = cufe; }
    public String getDispatchStatus() { return dispatchStatus; }
    public void setDispatchStatus(String dispatchStatus) { this.dispatchStatus = dispatchStatus; }
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) {
        boolean received = pdfPath != null && !pdfPath.isBlank() && !pdfPath.equals(this.pdfPath);
        this.pdfPath = pdfPath;
        if (received) {
            domainEvents.add(new com.sistemaspalacios.billing.domain.event.InvoiceDocumentUpdated(
                    id, clientNit, billingType, invoiceNumber));
        }
    }
    public String getQrContent() { return qrContent; }
    public void setQrContent(String qrContent) { this.qrContent = qrContent; }
    public String getQrImagePath() { return qrImagePath; }
    public void setQrImagePath(String qrImagePath) { this.qrImagePath = qrImagePath; }
    public String getClientNit() { return clientNit; }
    public void setClientNit(String clientNit) { this.clientNit = clientNit; }
    public String getClientBusinessName() { return clientBusinessName; }
    public void setClientBusinessName(String clientBusinessName) { this.clientBusinessName = clientBusinessName; }
    public String getClientTaxAddress() { return clientTaxAddress; }
    public void setClientTaxAddress(String clientTaxAddress) { this.clientTaxAddress = clientTaxAddress; }
    public String getClientBillingEmail() { return clientBillingEmail; }
    public void setClientBillingEmail(String clientBillingEmail) { this.clientBillingEmail = clientBillingEmail; }
    public Money getGrossAmount() { return grossAmount; }
    public Money getDiscountAmount() { return discountAmount; }
    public Money getSubtotalAmount() { return subtotalAmount; }
    public Money getIvaAmount() { return ivaAmount; }
    public Money getTotalAmount() { return totalAmount; }
    public Money getRetefuenteAmount() { return retefuenteAmount; }
    public Money getReteicaAmount() { return reteicaAmount; }
    public Money getReteivaAmount() { return reteivaAmount; }
    public Money getEstampillasAmount() { return estampillasAmount; }
    public Money getTotalWithholdings() { return totalWithholdings; }
    public Money getNetPayableAmount() { return netPayableAmount; }
    public LocalDate getIssueDate() { return issueDate; }
    public LocalDate getDueDate() { return dueDate; }
    public String getPaymentTerm() { return paymentTerm; }
    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { changeStatus(Objects.requireNonNull(status, "status cannot be null")); }
    public void markAsPaid() { changeStatus(InvoiceStatus.PAID); }

    /** Pagada por un pago concreto: el evento lleva el pago y la transacción para los frontends. */
    public void markAsPaid(UUID paymentId, String transactionId) { changeStatus(InvoiceStatus.PAID, paymentId, transactionId); }
    public Instant getVoidedAt() { return voidedAt; }
    public String getVoidReason() { return voidReason; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }
    public boolean isResidential() { return residential; }
    public void setResidential(boolean residential) { this.residential = residential; }

    // Versionado (Sprint 10)
    public int getVersionNumber() { return versionNumber; }
    public UUID getOriginInvoiceId() { return originInvoiceId != null ? originInvoiceId : id; }
    public UUID getPreviousInvoiceId() { return previousInvoiceId; }
    public UUID getSupersededById() { return supersededById; }
    public Instant getSupersededAt() { return supersededAt; }
    public String getRegenerationReason() { return regenerationReason; }
    public String getRegeneratedBy() { return regeneratedBy; }

    public Money getCreditNoteAmount() { return creditNoteAmount != null ? creditNoteAmount : Money.ZERO; }
    public void setCreditNoteAmount(Money creditNoteAmount) { this.creditNoteAmount = creditNoteAmount; }
    public Money getPaidAmount() { return paidAmount != null ? paidAmount : Money.ZERO; }
    public void setPaidAmount(Money paidAmount) { this.paidAmount = paidAmount; }

    /**
     * Saldo pendiente real de la factura (Sprint 13).
     * <p>
     * Si existe un saldo proveniente del corte de cartera de Siigo, **ese manda**: es la
     * fuente de verdad acordada con contabilidad. Solo cuando nunca se ha recibido un corte
     * para esta factura se usa la proyección local.
     * <p>
     * La diferencia entre ambos no se oculta: {@link #getBalanceDiscrepancy()} la expone
     * para conciliación.
     */
    public Money getPendingBalance() {
        if (this.status == InvoiceStatus.PAID) {
            return Money.ZERO;
        }
        if (hasSiigoBalance()) {
            Money effective = siigoBalance.minus(getUnreconciledLocalSettlement());
            return effective.isPositive() ? effective : Money.ZERO;
        }
        return getComputedPendingBalance();
    }

    /**
     * Saldo que el microservicio calcula con su propia información:
     * valor total menos notas crédito aplicadas menos pagos registrados.
     * <p>
     * Desde Sprint 13 es una **proyección**, no la verdad. Se conserva porque es lo único
     * que permite detectar que Siigo y el microservicio no coinciden.
     */
    public Money getComputedPendingBalance() {
        Money balance = totalAmount.subtract(getCreditNoteAmount()).subtract(getPaidAmount());
        return balance.isPositive() ? balance : Money.ZERO;
    }

    /**
     * Solo un saldo que llegó en un corte de cartera ({@link #applySiigoBalance}) es fuente de
     * verdad. Un valor en {@code saldo_siigo} sin fecha de corte no proviene de Siigo (p. ej. una
     * carga masiva que dejó 0.00) y no puede anular el saldo de una factura vigente.
     */
    public boolean hasSiigoBalance() {
        return siigoBalance != null && siigoBalanceCutoffAt != null;
    }

    public Money getSiigoBalance() { return siigoBalance; }
    public Instant getSiigoBalanceUpdatedAt() { return siigoBalanceUpdatedAt; }
    public Instant getSiigoBalanceCutoffAt() { return siigoBalanceCutoffAt; }
    public UUID getSiigoBalanceSnapshotId() { return siigoBalanceSnapshotId; }

    public Money getUnreconciledLocalSettlement() {
        return unreconciledLocalSettlement != null ? unreconciledLocalSettlement : Money.ZERO;
    }

    /** Un recaudo local reduce la deuda sin falsificar el saldo ni la fecha que informó Siigo. */
    public void recordLocalSettlement(Money amount, Instant settledAt) {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(settledAt);
        if (!amount.isPositive() || amount.compareTo(getPendingBalance()) > 0) {
            throw new IllegalArgumentException("El cruce debe ser positivo y no superar el saldo pendiente");
        }
        if (hasSiigoBalance()) {
            unreconciledLocalSettlement = getUnreconciledLocalSettlement().plus(amount);
            lastLocalSettlementAt = settledAt;
        }
        paidAmount = getPaidAmount().plus(amount);
    }

    /**
     * Diferencia entre lo que dice Siigo y lo que calcula el microservicio.
     * Positiva = Siigo reporta más saldo del que el sistema conoce (probable pago no
     * registrado en Siigo, o una nota crédito que Siigo no tiene).
     * Negativa = Siigo reporta menos (probable pago hecho directamente en Siigo).
     *
     * @return {@code null} si no hay corte con el que comparar
     */
    public Money getBalanceDiscrepancy() {
        if (!hasSiigoBalance()) {
            return null;
        }
        return siigoBalance.minus(getComputedPendingBalance());
    }

    /**
     * Aplica el saldo de un corte de cartera de Siigo.
     * <p>
     * El corte es una foto tomada en un instante. Si llega un archivo **más viejo** que el
     * saldo que ya tenemos aplicado, se rechaza: de lo contrario un corte de ayer revertiría
     * la cartera de hoy. Por eso la comparación es sobre la fecha de generación del corte y
     * no sobre la de su ingesta.
     *
     * @return {@code true} si el saldo se aplicó; {@code false} si el corte era obsoleto
     */
    public boolean applySiigoBalance(Money balance, Instant cutoffAt, UUID snapshotId) {
        Objects.requireNonNull(balance, "balance cannot be null");
        Objects.requireNonNull(cutoffAt, "cutoffAt cannot be null");
        if (cutoffAt.equals(this.siigoBalanceCutoffAt) && this.siigoBalance != null && !balance.equals(this.siigoBalance)) {
            throw new IllegalArgumentException("El mismo corte informa saldos contradictorios; requiere conciliación");
        }

        if (this.siigoBalanceCutoffAt != null && cutoffAt.isBefore(this.siigoBalanceCutoffAt)) {
            return false;
        }

        if (getUnreconciledLocalSettlement().isPositive() && !balance.equals(this.siigoBalance)) {
            Money expected = this.siigoBalance.minus(getUnreconciledLocalSettlement());
            if (!balance.equals(expected) || lastLocalSettlementAt == null || !cutoffAt.isAfter(lastLocalSettlementAt)) {
                throw new IllegalArgumentException("El corte no concilia los pagos locales pendientes de reflejar en Siigo");
            }
            unreconciledLocalSettlement = Money.ZERO;
        }
        if (status == InvoiceStatus.PAID && balance.isPositive() && getUnreconciledLocalSettlement().isZero()) {
            throw new IllegalArgumentException("El corte intenta reabrir una factura pagada; requiere conciliación");
        }

        this.siigoBalance = balance;
        this.siigoBalanceCutoffAt = cutoffAt;
        this.siigoBalanceUpdatedAt = Instant.now();
        this.siigoBalanceSnapshotId = snapshotId;

        // Consecuencia directa de que el saldo de Siigo sea la fuente de verdad: si Siigo
        // dice que no se debe nada, la factura está pagada aunque el pago se haya registrado
        // fuera del microservicio. No se toca una factura anulada ni una versión histórica.
        if (this.siigoBalance.isZero() && this.status == InvoiceStatus.CONSOLIDATED) {
            changeStatus(InvoiceStatus.PAID);
        }
        return true;
    }

    public boolean isFullyPaidOrCrossed() {
        return status == InvoiceStatus.PAID || getPendingBalance().isZero();
    }

    public void setDueDate(LocalDate date) { this.dueDate = date; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<InvoiceItem> getItems() { return Collections.unmodifiableList(items); }
    public List<WithholdingDetail> getWithholdingDetails() { return Collections.unmodifiableList(withholdingDetails); }
    public List<InvoiceExtraField> getExtraFields() { return Collections.unmodifiableList(extraFields); }

    public void addExtraField(ExtraFieldDefinition definition, String value) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(value, "value must not be null");
        for (InvoiceExtraField existing : extraFields) {
            if (existing.getDefinition().getId().equals(definition.getId()) ||
                    existing.getDefinition().getFieldKey().equalsIgnoreCase(definition.getFieldKey())) {
                existing.updateValue(value);
                return;
            }
        }
        extraFields.add(new InvoiceExtraField(UUID.randomUUID(), this.id, definition, value));
    }

    public Optional<String> getExtraFieldValue(String fieldKey) {
        if (fieldKey == null) {
            return Optional.empty();
        }
        return extraFields.stream()
                .filter(ef -> ef.getDefinition().getFieldKey().equalsIgnoreCase(fieldKey.trim()))
                .map(InvoiceExtraField::getValue)
                .findFirst();
    }

    public Instant getAvailableForPaymentAt() { return availableForPaymentAt; }
    public void setAvailableForPaymentAt(Instant availableForPaymentAt) { this.availableForPaymentAt = availableForPaymentAt; }
    public void markAvailableForPayment(Instant availableAt) {
        this.availableForPaymentAt = availableAt != null ? availableAt : Instant.now();
    }
    public boolean isImportDelayRecorded() { return importDelayRecorded; }
    public void setImportDelayRecorded(boolean importDelayRecorded) { this.importDelayRecorded = importDelayRecorded; }
    public LocalDate getPaidDate() { return paidDate; }
    public void setPaidDate(LocalDate paidDate) { this.paidDate = paidDate; }
}
