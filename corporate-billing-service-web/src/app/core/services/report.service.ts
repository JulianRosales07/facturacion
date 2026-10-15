import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { NicBillingReportResponse } from '../models/report.model';

@Injectable({
  providedIn: 'root'
})
export class ReportService {
  private readonly api = inject(ApiService);

  getBillingByNic(periodo: string): Observable<NicBillingReportResponse> {
    return this.api.get<NicBillingReportResponse>('/reportes/nic', {
      params: { periodo }
    });
  }
}
