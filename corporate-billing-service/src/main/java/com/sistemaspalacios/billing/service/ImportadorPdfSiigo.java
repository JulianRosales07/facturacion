package com.sistemaspalacios.billing.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Optional;

/**
 * Alias en español para SiigoPdfImporterService (regla DDD-Lite).
 */
@Service
public class ImportadorPdfSiigo {

    private final SiigoPdfImporterService delegate;

    public ImportadorPdfSiigo(SiigoPdfImporterService delegate) {
        this.delegate = delegate;
    }

    public SiigoPdfImporterService.PdfProcessingSummary procesarCarpetaCompartida() {
        return delegate.processSharedFolderPdfs();
    }

    public Optional<SiigoPdfImporterService.PdfMatchResult> clasificarYAsociarPdf(File archivoPdf) {
        return delegate.classifyAndLinkPdf(archivoPdf);
    }
}
