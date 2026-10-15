package com.sistemaspalacios.billing.service.dto;

import java.math.BigDecimal;

/**
 * Detalle individual de novedad o cambio contractual de órdenes para un cliente.
 */
public record ClientNoveltyDetailDto(
        String id,
        String tipoNovedad,
        String categoria,
        String sedeNombre,
        String codigoOrden,
        String fechaEfectiva,
        Integer diasProrrateo,
        BigDecimal valorOriginal,
        BigDecimal valorAjuste,
        String tipoImpacto,
        String observacion,
        String estado,
        // Trazabilidad (V27): de dónde viene y dónde se cobró.
        BigDecimal valorNuevo,
        String estadoProcesamiento,
        String detalleProcesamiento,
        String periodoNovedad,
        String facturaId,
        String numeroFactura
) {
    public ClientNoveltyDetailDto(String id, String tipoNovedad, String categoria, String sedeNombre, String codigoOrden,
                                  String fechaEfectiva, Integer diasProrrateo, BigDecimal valorOriginal, BigDecimal valorAjuste,
                                  String tipoImpacto, String observacion, String estado) {
        this(id, tipoNovedad, categoria, sedeNombre, codigoOrden, fechaEfectiva, diasProrrateo, valorOriginal, valorAjuste,
                tipoImpacto, observacion, estado, null, null, null, null, null, null);
    }

    public ClientNoveltyDetailDto {
        if (tipoImpacto == null) tipoImpacto = "SUMA";
        if (estado == null) estado = "PENDIENTE_FACTURAR";
        if (categoria == null) {
            categoria = switch (tipoNovedad != null ? tipoNovedad : "") {
                case "ALTA", "NUEVA_SEDE" -> "NUEVA_ADQUISICION";
                case "CAMBIO_PLAN", "CAMBIO_SERVICIO", "UPGRADE", "DOWNGRADE" -> "CAMBIO_SERVICIO";
                case "CUOTA_INSTALACION", "SVA", "ADICION" -> "ADICION";
                case "BAJA", "SUSPENSION" -> "RETIRO";
                case "REACTIVACION", "TRASLADO_DIRECCION" -> "CAMBIO_SERVICIO";
                default -> "OTRO";
            };
        }
    }
}
