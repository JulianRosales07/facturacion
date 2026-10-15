export interface InvoiceQr {
  contenido?: string;
  imagen?: string;
  estado?: string;
}

export interface PageResult<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number; }
export interface IncomingInvoice {
  id: string;
  invoiceNumber: string;
  clientNit: string;
  businessName: string;
  billingType: 'F1' | 'F20';
  issueDate: string;
  dueDate: string | null;
  total: number;
  saldo_pendiente?: number;
  detalle_oficial_confirmado?: boolean;
  saldo_a_favor?: number;
  pdfAvailable: boolean;
  cufe: string | null;
  qr?: InvoiceQr | null;
  estado_pago?: string;
  fecha_pago?: string | null;
  referencia_pago?: string | null;
  transaction_id?: string | null;
  valor_pagado?: number | null;
  proveedor_pago?: string | null;
  /** SIIGO = documento oficial recibido; INTERNO = generado en este sistema (en preparación). */
  origen?: InvoiceOrigin;
}
export type InvoiceOrigin = 'SIIGO' | 'INTERNO';
export interface InvoiceSummary { totalInvoices: number; withPdf: number; pendingPdf: number; }
export interface FileHistory { id: string; processedAt: string; filename: string; source: string; status: 'IMPORTADO' | 'RECHAZADO' | 'OMITIDO'; created: number; repeated: number; detail: string | null; username: string; }
export interface IncomingFilters {
  search?: string;
  number?: string;
  type?: string;
  period?: string;
  from?: string;
  to?: string;
  pdfAvailable?: string;
  estadoPago?: string;
  includePaid?: boolean;
  /** SIIGO (por omisión), INTERNO o TODAS. */
  origen?: string;
  page: number;
  size: number;
}
/** Factura dentro de un grupo por NIT (GET /facturas/recibidas/por-nit). */
export interface GroupedInvoice {
  id: string;
  tipo: 'F1' | 'F20';
  consecutivo: string;
  clave_documento: string;
  valor_servicio: number;
  saldo_pendiente?: number;
  detalle_oficial_confirmado?: boolean;
  saldo_a_favor?: number;
  fecha_emision: string;
  fecha_vencimiento: string | null;
  pdf_disponible: boolean;
  cufe: string | null;
  qr?: InvoiceQr | null;
  estado_pago?: string;
  fecha_pago?: string | null;
  referencia_pago?: string | null;
  transaction_id?: string | null;
  origen?: InvoiceOrigin;
}
/** Un único objeto por NIT; valor_total_servicio = suma de todas sus facturas, con discriminación de valor_pagado y valor_pendiente. */
export interface ClientInvoiceGroup {
  nit: string;
  razon_social: string | null;
  cantidad_facturas: number;
  facturas: GroupedInvoice[];
  valor_total_servicio: number;
  valor_pagado?: number;
  valor_pendiente?: number;
}
export type InvoiceListView = 'nit' | 'factura';

