package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;

import java.util.List;
import java.util.UUID;

/**
 * Application service for corporate consolidated billing operations orchestration (Sprint 4).
 */
public interface BillingService {

    /**
     * Genera una factura consolidada para un NIT o cliente específico en el periodo y corte dado.
     */
    Invoice generateConsolidatedInvoice(String periodCode, BillingType billingType, BillingCutType cutType, String nit);

    /**
     * Genera las facturas consolidadas para todos los clientes (o un NIT específico si se indica)
     * en el periodo y corte indicados.
     */
    List<Invoice> generateConsolidated(String periodCode, BillingType billingType, BillingCutType cutType, String nit);

    /**
     * Lista facturas aplicando filtros opcionales por periodo, NIT y estado.
     */
    List<Invoice> getInvoices(String periodCode, String nit, InvoiceStatus status);

    /**
     * Obtiene el agregado completo de una factura por su ID.
     */
    Invoice getInvoiceById(UUID invoiceId);

    /**
     * Anula una factura emitida registrando el motivo (HU-19).
     */
    void voidInvoice(UUID invoiceId, String reason);

    /**
     * Regenera la factura vigente de un cliente y periodo creando una NUEVA VERSIÓN
     * que acumula todas las novedades del mes (Sprint 10).
     * <p>
     * La versión anterior no se borra ni se anula: queda en estado
     * {@code REEMPLAZADA} como histórico inmutable, conserva su PDF oficial y apunta a
     * la versión que la sustituye, de modo que la cadena completa es auditable.
     * <p>
     * La nueva versión se recalcula desde cero con el estado actual de sedes activas,
     * novedades y cuotas de instalación del periodo, por lo que el valor a pagar refleja
     * el consolidado del mes completo para el NIT del cliente.
     *
     * @param invoiceId              factura vigente a regenerar
     * @param reason                 motivo de la regeneración; obligatorio, queda auditado
     * @param confirmTransmittedCufe autoriza regenerar una factura que ya tiene CUFE, es
     *                               decir ya transmitida a la DIAN. Sin esta confirmación
     *                               la operación se rechaza
     * @return la nueva versión vigente de la factura
     * @throws FacturaNoRegenerableException si el estado o el CUFE lo impiden
     */
    Invoice regenerateInvoice(UUID invoiceId, String reason, boolean confirmTransmittedCufe);

    /**
     * Incorpora las novedades del periodo a la factura en preparación del cliente,
     * recalculándola en su lugar (mismo id y número). No crea facturas: si el cliente no
     * tiene factura del periodo, las novedades entrarán al generarla; si la vigente ya no
     * es editable (enviada a Siigo, con CUFE, pagos o notas crédito), no se modifica.
     */
    com.sistemaspalacios.billing.service.dto.InvoiceRefreshResult refreshEditableInvoice(UUID clientId, UUID periodId);

    /**
     * Devuelve la cadena completa de versiones a la que pertenece la factura indicada,
     * ordenada de la versión 1 a la vigente.
     */
    List<Invoice> getInvoiceVersions(UUID invoiceId);

    /**
     * Obtiene la factura VIGENTE de un cliente para un periodo: la única que no está
     * anulada ni reemplazada. Es la que el cliente debe pagar.
     */
    Invoice getCurrentInvoiceForClientPeriod(String nit, String periodCode);

    /**
     * Genera la representación gráfica en HTML fiel a la factura oficial Siigo (F001000000519930000.PDF).
     */
    String renderInvoiceHtml(UUID invoiceId);

    /**
     * Genera el documento PDF oficial fiel a la factura Siigo (F001000000519930000.PDF).
     */
    byte[] renderInvoicePdf(UUID invoiceId);

    /**
     * Obtiene los bytes de la imagen PNG del código QR original oficial de la factura.
     * Soporta búsqueda por UUID o por referencia de documento (e.g., F1-51993, 51993).
     */
    byte[] getInvoiceQrImage(String reference);
}
