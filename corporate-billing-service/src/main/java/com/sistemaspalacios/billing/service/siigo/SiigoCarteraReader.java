package com.sistemaspalacios.billing.service.siigo;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.vo.Money;

import java.io.File;
import java.time.LocalDate;
import java.util.List;

/**
 * Puerto para leer el corte de cartera que Siigo deja en la carpeta compartida
 * (Sprint 13), típicamente {@code saldos_de_cartera.xlsx}.
 * <p>
 * El archivo tiene título en la fila 1, encabezados en la fila 5 y datos desde la fila 6,
 * en una hoja única. Las columnas relevantes son:
 * <pre>
 *   A  IDENTIFICACIÓN        -> NIT del cliente
 *   C  CUENTA                -> cuenta contable de cartera
 *   D  TIPO DE COMPROBANTE   -> 'F'
 *   E  CÓDIGO COMPROBANTE    -> 1 => F1 (FESP) | 20 => F20 (FEMG)
 *   F  NÚMERO DE DOCUMENTO   -> consecutivo de la factura
 *   H  FECHA VENCIMIENTO     -> entero yyyyMMdd
 *   L  SALDO                 -> saldo pendiente
 *   Q  FECHA DOCUMENTO       -> entero yyyyMMdd
 * </pre>
 */
public interface SiigoCarteraReader {

    /**
     * Lee y normaliza todas las filas de datos del corte.
     *
     * @param spreadsheet archivo {@code .xlsx} o {@code .xls}
     * @return resultado con las filas válidas y los errores de formato por fila
     */
    CarteraSnapshotData read(File spreadsheet);

    /**
     * Una fila del corte de cartera, ya normalizada a tipos del dominio.
     *
     * @param rowNumber        número de fila en la hoja, para poder auditar
     * @param clientNit        columna A
     * @param accountingAccount columna C
     * @param voucherType      columna D
     * @param voucherCode      columna E (1 o 20)
     * @param documentNumber   columna F, consecutivo
     * @param billingType      derivado del código de comprobante
     * @param balance          columna L, ya redondeado a 2 decimales HALF_UP
     * @param dueDate          columna H
     * @param documentDate     columna Q
     */
    record CarteraRow(
            int rowNumber,
            String clientNit,
            String accountingAccount,
            String voucherType,
            Integer voucherCode,
            String documentNumber,
            BillingType billingType,
            Money balance,
            LocalDate dueDate,
            LocalDate documentDate,
            String costCenter,
            String cityCode,
            String zoneCode
    ) {}

    /** Error de formato en una fila concreta; no aborta la lectura del resto. */
    record CarteraRowError(int rowNumber, String message) {}

    /**
     * @param sourceFileName nombre del archivo leído
     * @param cutoffDate     fecha de generación del corte. Se deriva de la fecha de
     *                       documento más reciente del archivo, no de la fecha del sistema,
     *                       para que un archivo viejo no se confunda con uno nuevo.
     * @param rows           filas válidas
     * @param errors         filas descartadas por formato
     */
    record CarteraSnapshotData(
            String sourceFileName,
            LocalDate cutoffDate,
            List<CarteraRow> rows,
            List<CarteraRowError> errors
    ) {
        public int totalRows() {
            return rows.size() + errors.size();
        }
    }
}
