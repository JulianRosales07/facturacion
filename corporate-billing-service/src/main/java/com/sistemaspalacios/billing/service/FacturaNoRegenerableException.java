package com.sistemaspalacios.billing.service;

import java.util.UUID;

/**
 * Excepción de negocio lanzada cuando una factura no puede regenerarse (Sprint 10).
 * <p>
 * Cubre dos situaciones distintas:
 * <ul>
 *   <li>El estado lo impide: la factura está anulada, ya fue reemplazada por una versión
 *       posterior, o ya fue pagada.</li>
 *   <li>La factura ya tiene CUFE, es decir fue transmitida a la DIAN. Regenerarla
 *       implicaría alterar un documento electrónico ya radicado, por lo que requiere
 *       confirmación explícita del operador.</li>
 * </ul>
 */
public class FacturaNoRegenerableException extends RuntimeException {

    private final UUID invoiceId;
    private final String reasonCode;

    public static final String REASON_ESTADO = "ESTADO_NO_PERMITE_REGENERACION";
    public static final String REASON_CUFE_TRANSMITIDO = "CUFE_YA_TRANSMITIDO";

    public FacturaNoRegenerableException(UUID invoiceId, String reasonCode, String message) {
        super(message);
        this.invoiceId = invoiceId;
        this.reasonCode = reasonCode;
    }

    public static FacturaNoRegenerableException dueToState(UUID invoiceId, String detail) {
        return new FacturaNoRegenerableException(invoiceId, REASON_ESTADO, detail);
    }

    public static FacturaNoRegenerableException dueToTransmittedCufe(UUID invoiceId, String invoiceNumber, String cufe) {
        return new FacturaNoRegenerableException(
                invoiceId,
                REASON_CUFE_TRANSMITIDO,
                String.format(
                        "La factura %s ya fue transmitida a la DIAN (CUFE %s). Regenerarla altera un documento "
                                + "electrónico radicado. Si la corrección está autorizada, repita la operación con "
                                + "\"confirmarCufeTransmitido\": true; de lo contrario emita una nota crédito.",
                        invoiceNumber != null ? invoiceNumber : invoiceId.toString(),
                        cufe != null && cufe.length() > 12 ? cufe.substring(0, 12) + "…" : cufe)
        );
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
