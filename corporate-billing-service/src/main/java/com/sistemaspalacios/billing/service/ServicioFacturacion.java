package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;

import java.util.List;
import java.util.UUID;

/**
 * Interfaz en español para el servicio de facturación consolidada corporativa.
 * Especificada en PROMPT_corporate-billing-service.md (Sprint 4).
 */
public interface ServicioFacturacion extends BillingService {

    default List<Invoice> generarConsolidada(String periodo, BillingType tipoFacturacion, BillingCutType tipoCorte, String nit) {
        return generateConsolidated(periodo, tipoFacturacion, tipoCorte, nit);
    }

    default Invoice generarConsolidadaPorNit(String periodo, BillingType tipoFacturacion, BillingCutType tipoCorte, String nit) {
        return generateConsolidatedInvoice(periodo, tipoFacturacion, tipoCorte, nit);
    }

    default void anular(UUID facturaId, String motivo) {
        voidInvoice(facturaId, motivo);
    }

    /**
     * Regenera la factura creando una nueva versión que acumula las novedades del periodo
     * y deja la anterior como histórico reemplazado (Sprint 10).
     */
    default Invoice regenerar(UUID facturaId, String motivo, boolean confirmarCufeTransmitido) {
        return regenerateInvoice(facturaId, motivo, confirmarCufeTransmitido);
    }

    /** Cadena completa de versiones de la factura, de la 1 a la vigente. */
    default List<Invoice> versiones(UUID facturaId) {
        return getInvoiceVersions(facturaId);
    }

    /** Factura vigente que debe pagar el cliente para el periodo indicado. */
    default Invoice facturaVigente(String nit, String periodo) {
        return getCurrentInvoiceForClientPeriod(nit, periodo);
    }
}
