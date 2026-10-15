package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.service.SiigoPdfImporterService;

import java.util.List;

public record ProcesarCarpetaResponseDto(
        int totalArchivosPdfEscaneados,
        int totalPdfsClasificados,
        int totalPdfsSinCoincidencia,
        List<SiigoPdfImporterService.PdfMatchResult> clasificaciones,
        List<String> archivosSinCoincidencia
) {
    public static ProcesarCarpetaResponseDto fromSummary(SiigoPdfImporterService.PdfProcessingSummary summary) {
        return new ProcesarCarpetaResponseDto(
                summary.totalScanned(),
                summary.totalMatched(),
                summary.totalUnmatched(),
                summary.matched(),
                summary.unmatched()
        );
    }
}
