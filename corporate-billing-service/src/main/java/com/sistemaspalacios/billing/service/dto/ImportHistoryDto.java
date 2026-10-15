package com.sistemaspalacios.billing.service.dto;

import com.sistemaspalacios.billing.domain.model.ImportedFile;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public record ImportHistoryDto(
        UUID id,
        String date,
        String method,
        String fileName,
        String result,
        String status,
        Integer totalRecords,
        Integer errorRecords,
        Instant receivedAt
) {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm")
            .withZone(ZoneId.of("America/Bogota"));

    public static ImportHistoryDto fromEntity(ImportedFile entity) {
        String formattedDate = entity.getReceivedAt() != null
                ? FORMATTER.format(entity.getReceivedAt())
                : "-";

        int total = entity.getTotalRecords() != null ? entity.getTotalRecords() : 0;
        int errors = entity.getErrorRecords() != null ? entity.getErrorRecords() : 0;
        int valid = Math.max(0, total - errors);

        String resultText;
        String statusText;

        if (errors == 0 && total > 0) {
            resultText = total + " registros válidos (0 errores)";
            statusText = "success";
        } else if (errors > 0 && valid > 0) {
            resultText = valid + " válidos · " + errors + " con advertencia/error";
            statusText = "warning";
        } else if (errors > 0 && valid == 0) {
            resultText = errors + " errores (0 procesados)";
            statusText = "error";
        } else {
            resultText = "0 registros procesados";
            statusText = "success";
        }

        String displayMethod = switch (entity.getImportMethod() != null ? entity.getImportMethod().toUpperCase() : "EXCEL") {
            case "CARPETA", "CARPETA_LOCAL" -> "Consumo de carpeta";
            case "CARPETA_RED", "SFTP" -> "Carpeta de red";
            default -> "Archivo Excel / Plano";
        };

        return new ImportHistoryDto(
                entity.getId(),
                formattedDate,
                displayMethod,
                entity.getFileName(),
                resultText,
                statusText,
                total,
                errors,
                entity.getReceivedAt()
        );
    }
}
