package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.domain.model.OrderOrigin;

public record FolderImportRequestDto(
        String folderPath,
        String periodCode,
        OrderOrigin origin
) {}
