package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.service.dto.FacturaSpPagosDto;

import java.util.List;
import java.util.UUID;

/**
 * Servicio de consulta y lectura de facturas estructuradas en JSON
 * para el sistema de recaudos y pasarela de pagos (SP Pagos).
 */
public interface SpPagosQueryService {

    /**
     * Consulta el detalle completo de una factura por su identificador UUID,
     * enriquecido con balances vigentes, notas crédito y enlaces de descarga.
     *
     * @param invoiceId Identificador único de la factura.
     * @return DTO optimizado para SP Pagos.
     */
    FacturaSpPagosDto getInvoiceForPayment(UUID invoiceId);

    /**
     * Lista facturas de un cliente para consulta o liquidación en la pasarela.
     *
     * @param nit            NIT del cliente (requerido para pasarela por seguridad).
     * @param numeroFactura  Filtro opcional por consecutivo o número completo.
     * @param soloPendientes Si es true, solo retorna facturas con saldo pendiente mayor a cero.
     * @return Lista de facturas en formato JSON para SP Pagos.
     */
    List<FacturaSpPagosDto> listInvoicesForPayment(String nit, String numeroFactura, boolean soloPendientes);

    /**
     * Obtiene la factura vigente de un cliente en un periodo contable específico.
     *
     * @param nit        NIT del cliente.
     * @param periodCode Código del periodo (ej. "2026-09").
     * @return Factura vigente en formato SP Pagos.
     */
    FacturaSpPagosDto getCurrentInvoiceForClientPeriod(String nit, String periodCode);
}
