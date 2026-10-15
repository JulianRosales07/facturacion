import { Injectable, inject } from '@angular/core';
import { ApiService } from './api.service';
import { IncomingFilters, IncomingInvoice, InvoiceSummary, FileHistory, PageResult, ClientInvoiceGroup } from '../models/incoming-invoice.model';
@Injectable({ providedIn: 'root' })
export class IncomingInvoiceService {
  private readonly api = inject(ApiService);
  list(filters: IncomingFilters) {
    return this.api.get<PageResult<IncomingInvoice>>('/facturas/recibidas', { params: this.toParams({ includePaid: true, ...filters }) });
  }
  /** Mismos filtros que {@link list}, pero un objeto por NIT. La paginación es por NIT. En el panel administrativo incluye todas las facturas. */
  listByNit(filters: IncomingFilters) {
    return this.api.get<PageResult<ClientInvoiceGroup>>('/facturas/recibidas/por-nit', { params: this.toParams({ includePaid: true, ...filters }) });
  }
  summary() { return this.api.get<InvoiceSummary>('/facturas/recibidas/resumen'); }
  history(page = 0, size = 10) { return this.api.get<PageResult<FileHistory>>('/siigo/facturas/historial', { params: { page, size } }); }
  private toParams(filters: IncomingFilters) {
    return Object.fromEntries(Object.entries(filters).filter(([,v]) => v !== undefined && v !== ''));
  }
}
