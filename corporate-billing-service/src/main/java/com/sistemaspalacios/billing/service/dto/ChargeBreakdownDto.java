package com.sistemaspalacios.billing.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "Conoce los cobros a detalle": la factura agrupada en Deuda anterior, Cargos fijos y
 * Otros conceptos. Mismos números para la representación gráfica y para la pantalla.
 *
 * @param totalFactura total oficial de la factura (cargos fijos + otros conceptos)
 * @param totalAPagar  total de la factura más la deuda anterior pendiente
 */
public record ChargeBreakdownDto(
        PreviousDebt deudaAnterior,
        ChargeBlock cargosFijos,
        ChargeBlock otrosConceptos,
        BigDecimal totalFactura,
        BigDecimal totalAPagar
) {
    /**
     * Facturas oficiales (Siigo) del cliente emitidas antes del periodo de esta factura.
     *
     * @param cargosAnteriores valor de esas facturas, descontadas sus notas crédito
     * @param pagosRecibidos   lo que ya se abonó a ellas
     * @param saldo            lo que sigue pendiente (corte de cartera de Siigo cuando existe)
     */
    public record PreviousDebt(BigDecimal cargosAnteriores, BigDecimal pagosRecibidos, BigDecimal saldo, int facturas) {}

    /** @param subtotal antes de IVA; {@code total = subtotal + impuestos} */
    public record ChargeBlock(List<ChargeLine> lineas, BigDecimal subtotal, BigDecimal impuestos, BigDecimal total) {}

    /**
     * @param valor     antes de IVA; negativo en descuentos y créditos
     * @param novedadId novedad de origen, si la línea viene de una
     * @param categoria subtítulo dentro de Otros conceptos (ver {@link Category}); null en cargos fijos
     */
    public record ChargeLine(String descripcion, LocalDate fechaInicial, LocalDate fechaFinal, BigDecimal valor,
                             UUID novedadId, String categoria) {
        public ChargeLine(String descripcion, LocalDate fechaInicial, LocalDate fechaFinal, BigDecimal valor, UUID novedadId) {
            this(descripcion, fechaInicial, fechaFinal, valor, novedadId, null);
        }
    }

    /** Subtítulos de Otros conceptos, en el orden en que se muestran. */
    public enum Category {
        NOVEDADES("Novedades del servicio"),
        CUOTAS("Cuotas de instalación"),
        AJUSTES("Ajustes y descuentos"),
        COBRANZA("Intereses y gestión de cobro");

        private final String title;

        Category(String title) { this.title = title; }

        public String title() { return title; }
    }
}
