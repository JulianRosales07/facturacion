import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PaymentWithholdingAudit, WithholdingAuditFilter, WithholdingAuditPage } from '../models/withholding-audit.model';

/** Auditoría de retenciones (GET /api/v1/admin/.../withholdings/audit). Solo consulta: la evidencia no se edita. */
@Injectable({ providedIn: 'root' })
export class WithholdingAuditService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin`;

  forPayment(paymentId: string): Observable<PaymentWithholdingAudit> {
    return this.http.get<PaymentWithholdingAudit>(`${this.base}/payments/${paymentId}/withholdings/audit`);
  }

  search(filter: WithholdingAuditFilter, page: number, size: number): Observable<WithholdingAuditPage> {
    return this.http.get<WithholdingAuditPage>(`${this.base}/withholdings/audit`,
      { params: WithholdingAuditService.params(filter).set('page', page).set('size', size) });
  }

  /** Evidencia con los mismos filtros: Excel (.xlsx con hojas Retenciones, Historial e Información) o CSV. */
  export(filter: WithholdingAuditFilter, format: 'xlsx' | 'csv'): Observable<Blob> {
    return this.http.get(`${this.base}/withholdings/audit/export`,
      { params: WithholdingAuditService.params(filter).set('format', format), responseType: 'blob' });
  }

  /** Descarga el archivo en el navegador. */
  static save(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(url);
  }

  static filename(format: 'xlsx' | 'csv', suffix = ''): string {
    return `auditoria-retenciones${suffix}-${new Date().toISOString().slice(0, 10)}.${format}`;
  }

  static params(filter: WithholdingAuditFilter): HttpParams {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(filter)) {
      const text = typeof value === 'string' ? value.trim() : '';
      if (text) params = params.set(key, text);
    }
    return params;
  }
}
