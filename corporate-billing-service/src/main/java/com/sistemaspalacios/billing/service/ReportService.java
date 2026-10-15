package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.service.dto.NicBillingReportResponse;

public interface ReportService {
    NicBillingReportResponse generateNicBillingReport(String periodo);
}
