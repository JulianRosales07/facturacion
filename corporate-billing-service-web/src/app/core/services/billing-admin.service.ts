import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface ClientBillingRuleItem {
  id: string;
  nit: string;
  razonSocial: string;
  tipoFacturacion: string;
  reglaFacturacion: 'ORDINARIA' | 'ESPECIAL_CORPORATIVA';
  diaCorteCorporativo?: number | null;
  diaCorteEfectivo: number;
  detalleReglaCorporativa?: string | null;
  configuracionCompleta: boolean;
  activo: boolean;
}

export interface UpdateBillingRulePayload {
  reglaFacturacion: 'ORDINARIA' | 'ESPECIAL_CORPORATIVA';
  diaCorteCorporativo?: number | null;
  detalleReglaCorporativa?: string | null;
}

export interface BillingIncidentItem {
  id: string;
  clienteId: string;
  nit: string;
  razonSocial: string;
  periodo: string;
  tipoIncidencia: 'FACTURA_FALTANTE' | 'CONFIGURACION_INCOMPLETA';
  estadoAtencion: 'ABIERTA' | 'EN_PROCESO' | 'RESUELTA';
  descripcion: string;
  diaCorteEsperado?: number | null;
  fechaCorteEsperada?: string | null;
  fechaDeteccion: string;
  fechaResolucion?: string | null;
  observacionesResolucion?: string | null;
  responsableAsignado?: string | null;
  observacionesSeguimiento?: string | null;
  notificacionEnviada: boolean;
  canalNotificacion?: string | null;
}

export interface BillingCalendarSummary {
  periodo: string;
  fechaEvaluacion: string;
  totalClientesActivos: number;
  disponiblesATiempo: number;
  disponiblesConRetraso: number;
  pendientesDentroPlazo: number;
  incidenciasFacturaFaltante: number;
  configuracionesIncompletas: number;
  clientes: Array<{
    nit: string;
    businessName: string;
    billingRule: string;
    corporateRuleDetails?: string;
    corporateCutoffDay?: number;
    period: string;
    evaluationDate: string;
    status: string;
    availableForPayment: boolean;
    invoiceNumber?: string;
    observations?: string;
  }>;
}

@Injectable({
  providedIn: 'root'
})
export class BillingAdminService {
  private readonly api = inject(ApiService);

  listClientRules(): Observable<ClientBillingRuleItem[]> {
    return this.api.get<ClientBillingRuleItem[]>('/admin/reglas-facturacion/clientes');
  }

  updateClientBillingRule(id: string, payload: UpdateBillingRulePayload): Observable<ClientBillingRuleItem> {
    return this.api.put<ClientBillingRuleItem>(`/admin/reglas-facturacion/clientes/${id}`, payload);
  }

  listIncidents(periodo?: string, estado?: string): Observable<BillingIncidentItem[]> {
    const params: Record<string, string> = {};
    if (periodo) params['periodo'] = periodo;
    if (estado) params['estado'] = estado;
    return this.api.get<BillingIncidentItem[]>('/admin/incidencias-facturacion', { params });
  }

  triggerDetection(periodo: string, fechaEvaluacion?: string): Observable<BillingIncidentItem[]> {
    const params: Record<string, string> = { periodo };
    if (fechaEvaluacion) params['fechaEvaluacion'] = fechaEvaluacion;
    return this.api.post<BillingIncidentItem[]>('/admin/incidencias-facturacion/detectar', null, { params });
  }

  resolveIncident(id: string, observaciones: string, resueltoPor?: string): Observable<BillingIncidentItem> {
    return this.api.put<BillingIncidentItem>(`/admin/incidencias-facturacion/${id}/resolver`, {
      observaciones,
      resueltoPor
    });
  }

  assignIncident(id: string, responsable: string, observaciones?: string): Observable<BillingIncidentItem> {
    return this.api.put<BillingIncidentItem>(`/admin/incidencias-facturacion/${id}/asignar`, {
      responsable,
      observaciones
    });
  }

  getBillingCalendar(periodo: string, fechaEvaluacion?: string): Observable<BillingCalendarSummary> {
    const params: Record<string, string> = { periodo };
    if (fechaEvaluacion) params['fechaEvaluacion'] = fechaEvaluacion;
    return this.api.get<BillingCalendarSummary>('/admin/calendario-facturacion', { params });
  }
}
