package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;
import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentItem;
import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioDocumentoAjuste;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of CreditNoteService and ServicioNotaCredito (Sprint 5, HU-20).
 */
@Service
@Transactional
public class CreditNoteServiceImpl implements CreditNoteService, ServicioNotaCredito {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CreditNoteServiceImpl.class);

    private final RepositorioDocumentoAjuste repositorioDocumentoAjuste;
    private final RepositorioFactura repositorioFactura;
    private final com.sistemaspalacios.billing.service.security.CurrentUserProvider currentUserProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public CreditNoteServiceImpl(
            RepositorioDocumentoAjuste repositorioDocumentoAjuste,
            RepositorioFactura repositorioFactura,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.sistemaspalacios.billing.service.security.CurrentUserProvider currentUserProvider
    ) {
        this.repositorioDocumentoAjuste = Objects.requireNonNull(repositorioDocumentoAjuste, "repositorioDocumentoAjuste cannot be null");
        this.repositorioFactura = Objects.requireNonNull(repositorioFactura, "repositorioFactura cannot be null");
        this.currentUserProvider = currentUserProvider;
    }

    public CreditNoteServiceImpl(
            RepositorioDocumentoAjuste repositorioDocumentoAjuste,
            RepositorioFactura repositorioFactura
    ) {
        this(repositorioDocumentoAjuste, repositorioFactura, null);
    }

    @Override
    public AdjustmentDocument issueCreditNote(UUID invoiceId, String reason, String documentNumber, List<AdjustmentItemCommand> items) {
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("El motivo de la nota crédito es obligatorio");
        }

        Invoice invoice = repositorioFactura.findById(invoiceId)
                .orElseThrow(() -> new NoSuchElementException("Factura no encontrada con ID: " + invoiceId));
        if (invoice.isExternalDocument()) throw new IllegalStateException("Las notas crédito de esta factura se emiten en Siigo");

        if (invoice.isVoided()) {
            throw new IllegalStateException("No se puede emitir una nota crédito sobre una factura que se encuentra anulada");
        }
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new IllegalStateException("No se puede emitir una nota crédito sobre una factura en estado borrador");
        }

        String resolvedDocNumber = (documentNumber != null && !documentNumber.isBlank())
                ? documentNumber
                : generateCreditNoteNumber(invoice);

        AdjustmentDocument adjustmentDocument = new AdjustmentDocument(
                UUID.randomUUID(),
                invoiceId,
                AdjustmentDocumentType.CREDIT_NOTE,
                resolvedDocNumber,
                reason.trim()
        );

        if (items == null || items.isEmpty()) {
            // Nota crédito total reflejando todos los ítems de la factura
            if (invoice.getItems().isEmpty()) {
                throw new IllegalStateException("La factura a ajustar no tiene ítems facturables");
            }
            for (InvoiceItem invItem : invoice.getItems()) {
                AdjustmentDocumentItem adjItem = new AdjustmentDocumentItem(
                        UUID.randomUUID(),
                        adjustmentDocument.getId(),
                        invItem.getId(),
                        invItem.getDescription(),
                        invItem.getQuantity(),
                        invItem.getUnitPrice(),
                        invItem.getDiscountValue(),
                        invItem.getIvaPercentage()
                );
                adjustmentDocument.addItem(adjItem);
            }
        } else {
            // Nota crédito parcial con ítems especificados
            for (AdjustmentItemCommand cmd : items) {
                if (cmd.invoiceItemId() != null) {
                    boolean itemExists = invoice.getItems().stream()
                            .anyMatch(i -> i.getId().equals(cmd.invoiceItemId()));
                    if (!itemExists) {
                        throw new IllegalArgumentException("El ítem facturable con ID " + cmd.invoiceItemId() + " no pertenece a la factura " + invoiceId);
                    }
                }
                AdjustmentDocumentItem adjItem = new AdjustmentDocumentItem(
                        UUID.randomUUID(),
                        adjustmentDocument.getId(),
                        cmd.invoiceItemId(),
                        cmd.description() != null ? cmd.description() : "Ajuste de facturación",
                        cmd.quantity() != null ? cmd.quantity() : BigDecimal.ONE,
                        cmd.unitPrice() != null ? cmd.unitPrice() : Money.ZERO,
                        cmd.discount() != null ? cmd.discount() : Money.ZERO,
                        cmd.ivaRate() != null ? cmd.ivaRate() : BigDecimal.valueOf(19)
                );
                adjustmentDocument.addItem(adjItem);
            }
        }

        // Validar que el total de la nota crédito no supere el valor total oficial de la factura
        if (adjustmentDocument.getNetAmount().isGreaterThan(invoice.getTotalAmount())) {
            throw new IllegalArgumentException(String.format(
                    "El valor neto de la nota crédito ($%s) no puede superar el valor total de la factura ($%s)",
                    adjustmentDocument.getNetAmount().getAmount(),
                    invoice.getTotalAmount().getAmount()
            ));
        }

        String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";
        adjustmentDocument.setCreatedBy(currentUser);
        adjustmentDocument.apply();

        AdjustmentDocument saved = repositorioDocumentoAjuste.saveAndFlush(adjustmentDocument);
        log.info("Nota crédito {} emitida por usuario '{}' para factura ID {}", resolvedDocNumber, currentUser, invoiceId);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdjustmentDocument> getCreditNotesByInvoice(UUID invoiceId) {
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        return repositorioDocumentoAjuste.findByInvoiceId(invoiceId);
    }

    @Override
    @Transactional(readOnly = true)
    public AdjustmentDocument getCreditNoteById(UUID creditNoteId) {
        Objects.requireNonNull(creditNoteId, "creditNoteId cannot be null");
        return repositorioDocumentoAjuste.findById(creditNoteId)
                .orElseThrow(() -> new NoSuchElementException("Nota crédito no encontrada con ID: " + creditNoteId));
    }

    private String generateCreditNoteNumber(Invoice invoice) {
        String base = invoice.getInvoiceNumber() != null
                ? invoice.getInvoiceNumber().replace(" ", "-")
                : invoice.getId().toString().substring(0, 8).toUpperCase();
        return "NC-" + base;
    }
}
