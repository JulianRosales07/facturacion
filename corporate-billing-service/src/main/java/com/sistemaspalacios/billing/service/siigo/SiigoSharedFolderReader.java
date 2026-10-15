package com.sistemaspalacios.billing.service.siigo;

import java.io.File;
import java.util.List;

/**
 * Port for scanning and reading files (flat files and PDFs) in the shared folder on the server.
 */
public interface SiigoSharedFolderReader {

    /**
     * Checks if the shared folder exists and is accessible.
     */
    boolean isFolderAccessible();

    /**
     * Returns the absolute path of the configured shared folder.
     */
    String getSharedFolderPath();

    /**
     * Lists all PDF files currently waiting in the shared folder.
     */
    List<File> listPendingPdfFiles();

    /**
     * Lists all flat confirmation files in the shared folder (e.g. *.txt, *.csv, *.dat, *CONSECUTIVO*).
     */
    List<File> listConfirmationFlatFiles();

    /**
     * Lista las hojas de cálculo de corte de cartera que Siigo deja en la carpeta
     * compartida (Sprint 13), típicamente {@code saldos_de_cartera.xlsx}.
     * <p>
     * Se separa de {@link #listConfirmationFlatFiles()} porque son documentos distintos:
     * el plano de confirmación reporta consecutivos y CUFE de un lote recién exportado,
     * mientras que este archivo es un **corte de saldos** de toda la cartera.
     */
    List<File> listCarteraSpreadsheets();
}
