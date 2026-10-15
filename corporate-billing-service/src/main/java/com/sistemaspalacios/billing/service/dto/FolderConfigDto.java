package com.sistemaspalacios.billing.service.dto;

import java.util.List;

public record FolderConfigDto(
        String configuredPath,
        String absolutePath,
        boolean accessible,
        int filesCount,
        List<String> pendingFiles
) {}
