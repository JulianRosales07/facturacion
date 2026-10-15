import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import {
  FacturaResumenDto,
  FacturaDetalleDto,
  FacturaVersionDto,
  GenerarFacturaRequest,
  GenerarFacturasResponse,
  RegenerarFacturaRequest,
  InvoiceFilters,
  CorporateClientOption,
  ClientNoveltiesSummary,
  DetalleCobrosDto,
} from '../models/invoice.model';
import { FacturaSpPagosDto } from '../models/sp-pagos.model';
import { NotaCreditoResponseDto, EmitirNotaCreditoRequest } from '../models/credit-note.model';

@Injectable({
  providedIn: 'root'
})
export class InvoiceService {
  private readonly api = inject(ApiService);

  importOfficialInvoices(file: File): Observable<string[]> {
    const form = new FormData();
    form.append('archivo', file);
    return this.api.post<string[]>('/siigo/facturas/importar', form);
  }

  getInvoices(filters?: InvoiceFilters): Observable<FacturaResumenDto[]> {
    const params: Record<string, string | number | boolean> = {};
    for (const [key, value] of Object.entries(filters ?? {})) {
      if (value !== undefined && value !== '') params[key] = value;
    }
    return this.api.get<FacturaResumenDto[]>('/facturas', { params });
  }

  getClientInvoices(nit: string, filters?: { mes?: string; fechaDesde?: string; fechaHasta?: string; estado?: string }): Observable<FacturaResumenDto[]> {
    return this.getInvoices({ nit, periodo: filters?.mes, fechaDesde: filters?.fechaDesde, fechaHasta: filters?.fechaHasta, estado: filters?.estado });
  }
  getInvoiceById(id: string): Observable<FacturaDetalleDto> { return this.api.get<FacturaDetalleDto>(`/facturas/${id}`); }
  generateInvoices(request: GenerarFacturaRequest): Observable<GenerarFacturasResponse> {
    return this.api.post<GenerarFacturasResponse>('/facturas/generar', request);
  }

  voidInvoice(id: string, motivo: string): Observable<{ id: string; estado: string; motivo: string }> {
    return this.api.post<{ id: string; estado: string; motivo: string }>(`/facturas/${id}/anular`, { motivo });
  }

  regenerateInvoice(id: string, request: RegenerarFacturaRequest): Observable<Record<string, unknown>> {
    return this.api.post<Record<string, unknown>>(`/facturas/${id}/regenerar`, request);
  }

  /** Deuda anterior, cargos fijos y otros conceptos (novedades) de la factura. */
  getChargeBreakdown(id: string): Observable<DetalleCobrosDto> {
    return this.api.get<DetalleCobrosDto>(`/facturas/${id}/detalle-cobros`);
  }

  getInvoiceVersions(id: string): Observable<FacturaVersionDto[]> {
    return this.api.get<FacturaVersionDto[]>(`/facturas/${id}/versiones`);
  }

  getCurrentInvoiceForClient(nit: string, periodo: string): Observable<FacturaDetalleDto> {
    return this.api.get<FacturaDetalleDto>(`/facturas/clientes/${nit}/periodos/${periodo}/vigente`);
  }

  issueCreditNote(invoiceId: string, request: EmitirNotaCreditoRequest): Observable<NotaCreditoResponseDto> {
    return this.api.post<NotaCreditoResponseDto>(`/facturas/${invoiceId}/notas-credito`, request);
  }

  getCreditNotesByInvoice(invoiceId: string): Observable<NotaCreditoResponseDto[]> {
    return this.api.get<NotaCreditoResponseDto[]>(`/facturas/${invoiceId}/notas-credito`);
  }

  renderInvoiceGraphic(id: string): Observable<string> {
    return this.api.getText(`/facturas/${id}/representacion-grafica`);
  }

  getInvoicePdfUrl(id: string): string {
    return `/api/v1/facturas/${id}/pdf`;
  }

  getCorporateClients(): Observable<CorporateClientOption[]> {
    return this.api.get<CorporateClientOption[]>('/clientes');
  }
  getClientNovelties(nit: string, periodCode: string): Observable<ClientNoveltiesSummary> {
    return this.api.get<ClientNoveltiesSummary>(`/sincronizacion/novedades/clientes/${encodeURIComponent(nit)}`, { params: { periodo: periodCode } });
  }
  consumeClientNovelties(nit: string, periodCode: string): Observable<ClientNoveltiesSummary> {
    return this.api.post<ClientNoveltiesSummary>('/sincronizacion/consumir-novedades', { nit, periodo: periodCode });
  }

  getSpPagosInvoice(id: string): Observable<FacturaSpPagosDto> {
    return this.api.get<FacturaSpPagosDto>(`/pasarela/facturas/${id}`);
  }

  getSpPagosInvoicesByNit(nit: string, soloPendientes = true): Observable<FacturaSpPagosDto[]> {
    return this.api.get<FacturaSpPagosDto[]>('/pasarela/facturas', { params: { nit, soloPendientes } });
  }
}
