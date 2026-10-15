package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;
import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentStatus;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.Payment;
import com.sistemaspalacios.billing.domain.model.PaymentInvoice;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.AdjustmentDocumentRepository;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.service.dto.FacturaSpPagosDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SpPagosQueryServiceImpl implements SpPagosQueryService {

    private final RepositorioFactura repositorioFactura;
    private final RepositorioPago repositorioPago;
    private final AdjustmentDocumentRepository adjustmentDocumentRepository;

    /** Para informar el periodo del servicio de la factura (no el mes de emisión). Opcional. */
    private com.sistemaspalacios.billing.repository.RepositorioPeriodo repositorioPeriodo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRepositorioPeriodo(com.sistemaspalacios.billing.repository.RepositorioPeriodo repositorioPeriodo) {
        this.repositorioPeriodo = repositorioPeriodo;
    }

    /**
     * Periodo de la factura: el que tiene asignado (para Siigo, el del servicio según su
     * detalle); si no se conoce, el mes de emisión.
     */
    private String periodCodeOf(Invoice invoice) {
        if (repositorioPeriodo != null && invoice.getPeriodId() != null) {
            var period = repositorioPeriodo.findById(invoice.getPeriodId());
            if (period.isPresent()) return period.get().getCode();
        }
        return invoice.getIssueDate() != null ? java.time.YearMonth.from(invoice.getIssueDate()).toString() : null;
    }

    public SpPagosQueryServiceImpl(
            RepositorioFactura repositorioFactura,
            RepositorioPago repositorioPago,
            AdjustmentDocumentRepository adjustmentDocumentRepository
    ) {
        this.repositorioFactura = Objects.requireNonNull(repositorioFactura, "repositorioFactura cannot be null");
        this.repositorioPago = Objects.requireNonNull(repositorioPago, "repositorioPago cannot be null");
        this.adjustmentDocumentRepository = Objects.requireNonNull(adjustmentDocumentRepository, "adjustmentDocumentRepository cannot be null");
    }

    @Override
    @Transactional(readOnly = true)
    public FacturaSpPagosDto getInvoiceForPayment(UUID invoiceId) {
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        Invoice invoice = repositorioFactura.findById(invoiceId)
                .orElseThrow(() -> new NoSuchElementException("Factura no encontrada con ID: " + invoiceId));
        enrichBalances(invoice);
        String periodCode = periodCodeOf(invoice);
        var cartera = buildCarteraForInvoice(invoice, periodCode);
        return FacturaSpPagosDto.fromDomain(invoice, periodCode, cartera);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FacturaSpPagosDto> listInvoicesForPayment(String nit, String numeroFactura, boolean soloPendientes) {
        List<Invoice> invoices;
        if (nit != null && !nit.isBlank()) {
            invoices = repositorioFactura.findByClientNit(nit.trim());
        } else {
            invoices = repositorioFactura.findAll();
        }

        String filterNumber = (numeroFactura != null && !numeroFactura.isBlank())
                ? normalizedNumber(numeroFactura)
                : null;

        return invoices.stream()
                .filter(inv -> inv.getStatus() != InvoiceStatus.VOIDED && inv.getStatus() != InvoiceStatus.SUPERSEDED)
                .filter(inv -> {
                    if (filterNumber == null) return true;
                    String invNumber = normalizedNumber(inv.getInvoiceNumber());
                    return invNumber.equals(filterNumber) || (inv.getInvoiceNumber() != null && inv.getInvoiceNumber().equalsIgnoreCase(numeroFactura.trim()));
                })
                .peek(this::enrichBalances)
                .filter(inv -> {
                    if (!soloPendientes) return true;
                    return inv.getPendingBalance().isPositive();
                })
                .sorted((a, b) -> {
                    if (a.getIssueDate() == null || b.getIssueDate() == null) return 0;
                    return b.getIssueDate().compareTo(a.getIssueDate());
                })
                .map(inv -> {
                    String pCode = periodCodeOf(inv);
                    var cartera = buildCarteraForInvoice(inv, pCode);
                    return FacturaSpPagosDto.fromDomain(inv, pCode, cartera);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FacturaSpPagosDto getCurrentInvoiceForClientPeriod(String nit, String periodCode) {
        Objects.requireNonNull(nit, "nit cannot be null");
        Objects.requireNonNull(periodCode, "periodCode cannot be null");

        List<Invoice> clientInvoices = repositorioFactura.findByClientNit(nit.trim());
        Invoice current = clientInvoices.stream()
                .filter(Invoice::isCurrentVersion)
                .filter(inv -> periodCode.trim().equals(periodCodeOf(inv)))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No existe factura vigente para el cliente " + nit + " en el periodo " + periodCode));

        enrichBalances(current);
        var cartera = buildCarteraForInvoice(current, periodCode);
        return FacturaSpPagosDto.fromDomain(current, periodCode, cartera);
    }

    private FacturaSpPagosDto.CarteraClienteSpPagosDto buildCarteraForInvoice(Invoice current, String currentPeriodCode) {
        if (current.getClientNit() == null || current.getClientNit().isBlank()) {
            return null;
        }
        List<Invoice> clientInvoices = repositorioFactura.findByClientNit(current.getClientNit().trim());
        java.util.List<FacturaSpPagosDto.ObligacionCarteraDto> obligacionesAnteriores = new java.util.ArrayList<>();
        Money carteraAnterior = Money.ZERO;

        for (Invoice other : clientInvoices) {
            if (other.getId().equals(current.getId())) {
                continue;
            }
            if (!other.isCurrentVersion() || other.getStatus() == InvoiceStatus.VOIDED || other.getStatus() == InvoiceStatus.SUPERSEDED) {
                continue;
            }
            enrichBalances(other);
            if (!other.getPendingBalance().isPositive()) {
                continue;
            }
            String otherPeriod = periodCodeOf(other);
            boolean isPrevious = false;
            if (currentPeriodCode != null && otherPeriod != null) {
                int cmp = otherPeriod.compareTo(currentPeriodCode);
                if (cmp < 0) {
                    isPrevious = true;
                } else if (cmp == 0) {
                    if (other.getIssueDate() != null && current.getIssueDate() != null) {
                        isPrevious = other.getIssueDate().isBefore(current.getIssueDate());
                    }
                }
            } else if (other.getIssueDate() != null && current.getIssueDate() != null) {
                isPrevious = other.getIssueDate().isBefore(current.getIssueDate());
            }

            if (isPrevious) {
                carteraAnterior = carteraAnterior.plus(other.getPendingBalance());
                obligacionesAnteriores.add(new FacturaSpPagosDto.ObligacionCarteraDto(
                        other.getId(),
                        other.getBillingType() != null ? other.getBillingType().name() : "F1",
                        other.getInvoiceNumber(),
                        otherPeriod,
                        other.getIssueDate(),
                        other.getDueDate(),
                        other.getTotalAmount().amount(),
                        other.getPaidAmount().amount(),
                        other.getPendingBalance().amount(),
                        other.getStatus().getDbValue(),
                        other.getCufe()
                ));
            }
        }

        Money totalPorCobrar = current.getPendingBalance().plus(carteraAnterior);
        int totalObligaciones = obligacionesAnteriores.size() + (current.getPendingBalance().isPositive() ? 1 : 0);

        return new FacturaSpPagosDto.CarteraClienteSpPagosDto(
                current.getTotalAmount().amount(),
                current.getPendingBalance().amount(),
                carteraAnterior.amount(),
                totalPorCobrar.amount(),
                totalObligaciones,
                obligacionesAnteriores
        );
    }

    private void enrichBalances(Invoice invoice) {
        List<AdjustmentDocument> creditNotes = adjustmentDocumentRepository
                .findByInvoiceIdAndStatus(invoice.getId(), AdjustmentDocumentStatus.APPLIED);
        Money totalCn = Money.ZERO;
        for (AdjustmentDocument cn : creditNotes) {
            totalCn = totalCn.plus(cn.getNetAmount());
        }
        invoice.setCreditNoteAmount(totalCn);

        List<Payment> payments = repositorioPago.findAppliedPaymentsByInvoiceId(invoice.getId());
        Money totalPaid = Money.ZERO;
        java.time.LocalDate latestPaymentDate = null;
        for (Payment p : payments) {
            for (PaymentInvoice crossing : p.getInvoiceCrossings()) {
                if (crossing.getInvoiceId().equals(invoice.getId())) {
                    totalPaid = totalPaid.plus(crossing.getAppliedAmount());
                    if (p.getPaymentDate() != null && (latestPaymentDate == null || p.getPaymentDate().isAfter(latestPaymentDate))) {
                        latestPaymentDate = p.getPaymentDate();
                    }
                }
            }
        }
        invoice.setPaidAmount(totalPaid);
        if (latestPaymentDate != null) {
            invoice.setPaidDate(latestPaymentDate);
        }
    }

    private String normalizedNumber(String number) {
        if (number == null) return "";
        var matcher = Pattern.compile("(\\d+)$").matcher(number.trim());
        return matcher.find() ? matcher.group(1).replaceFirst("^0+(?!$)", "") : "";
    }
}
