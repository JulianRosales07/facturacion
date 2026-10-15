import { TaxConceptCode } from './tax.model';

export interface InvoiceItemSummary {
  invoiceId: string;
  invoiceNumber: string;
  issueDate: string;
  dueDate: string;
  totalAmount: number;
  creditNoteAmount: number;
  paidAmount: number;
  pendingBalance: number;
}

export interface ClientPortfolioSummary {
  clientNit: string;
  clientBusinessName: string;
  upToDate: boolean;
  totalPendingInvoices: number;
  totalOutstandingDebt: number;
  pendingInvoices: InvoiceItemSummary[];
  notice?: string;
}

/** Evaluación de un concepto seleccionado; el backend decide si aplica (HU-52). */
export interface WithholdingLine {
  conceptoId: string;
  nombre: string;
  tipo: TaxConceptCode;
  porcentaje: number;
  version: number;
  baseEvaluada: number;
  baseMinima: number;
  aplica: boolean;
  motivo?: string | null;
  valorRetenido: number;
}

export interface SettledInvoice {
  id: string;
  tipo: string;
  numero: string;
  subtotalOficial: number;
  ivaOficial: number;
  totalOficial: number;
  valorLiquidado: number;
  baseLiquidada: number;
  ivaLiquidado: number;
}

/** Liquidación oficial (POST /pagos/simular): misma estructura en simulación, checkout y registro. */
export interface PaymentSimulationResult {
  nit: string;
  razonSocial: string;
  facturas: SettledInvoice[];
  totalFacturas: number;
  baseGravable: number;
  totalIva: number;
  retenciones: WithholdingLine[];
  totalRetenciones: number;
  netoAntesRedondeo: number;
  ajusteRedondeo: number;
  netoAPagar: number;
  applicationMode: RetentionApplicationMode;
}

export interface PaymentResult {
  paymentId: string;
  clientNit: string;
  paymentDate: string;
  paymentMethod: string;
  bankAccount: string;
  grossAmount: number;
  totalWithholdings: number;
  roundingAmount: number;
  netPaidAmount: number;
  transactionNumber: string;
  paidInvoiceIds: string[];
  partialPayment: boolean;
  partialPaymentNotice?: string;
  remainingPendingInvoicesCount: number;
  remainingPendingDebt: number;
  status?: string;
}

export interface PaymentDeduction {
  validityId: string;
  percentage?: number;
  amount?: number;
  reason: string;
}

/** Concepto del catálogo con el porcentaje que se practica en este pago. */
export interface ConceptSelection {
  conceptId: string;
  percentage: number;
}

export type RetentionApplicationMode = 'PER_INVOICE' | 'CONSOLIDATED';

export interface SimulatePaymentRequest {
  /** Obligatorio: el backend nunca infiere el modo. Este panel liquida sobre el total. */
  applicationMode: RetentionApplicationMode;
  paymentDate?: string;
  deductions?: PaymentDeduction[];
  clientNit: string;
  invoiceIds: string[];
  /** Conceptos con el porcentaje ingresado (el analista lo digita como lo informa el cliente). */
  selectedConcepts: ConceptSelection[];
}

export interface RegisterPaymentRequest {
  applicationMode: RetentionApplicationMode;
  idempotencyKey?: string;
  expectedNetAmount?: number;
  deductions?: PaymentDeduction[];
  clientNit: string;
  invoiceIds: string[];
  /** Conceptos con el porcentaje ingresado (el analista lo digita como lo informa el cliente). */
  selectedConcepts: ConceptSelection[];
  paymentDate: string;
  paymentMethod: string;
  bankAccount: string;
  transactionNumber: string;
  observation?: string;
}

/** Snapshot inmutable de una retención tal como se liquidó en el pago (HU-54). */
export interface PaymentWithholdingSnapshot {
  conceptoId: string;
  nombre: string;
  tipo: TaxConceptCode;
  /** Porcentaje que se pagó (ingresado al pagar). */
  porcentaje: number;
  /** Máximo permitido por el catálogo al liquidar (null en pagos anteriores al cambio). */
  porcentajeMaximo?: number | null;
  version: number;
  vigenteDesde?: string | null;
  vigenteHasta?: string | null;
  baseCalculada: number;
  baseMinima: number;
  aplica: boolean;
  motivoNoAplica?: string | null;
  valorRetenido: number;
  motivoAjuste?: string | null;
  /** null: retención calculada sobre el pago consolidado. */
  facturaId?: string | null;
  facturaNumero?: string | null;
  /** Auditoría: porcentaje que escribió la persona (el aplicado es {@link porcentaje}). */
  porcentajeIngresado?: number | null;
  estadoSeleccion?: 'SELECTED' | 'NOT_APPLICABLE' | 'APPLIED' | 'CANCELLED' | null;
  /** Hora del servidor, en -05:00 (Colombia). */
  seleccionadoEn?: string | null;
  aplicadoEn?: string | null;
  canal?: 'PAYMENT_GATEWAY' | 'ADMIN_WEB' | 'MANUAL_PAYMENT' | 'API' | null;
  seleccionadoPor?: string | null;
}

export interface PaymentDetail {
  pagoId: string;
  estado: 'PENDIENTE' | 'APLICADO' | 'RECHAZADO' | 'ANULADO' | string;
  nit: string;
  razonSocial: string;
  fechaPago: string;
  medioPago: string;
  cuentaBanco: string;
  numeroTransaccion?: string | null;
  totalFacturas: number;
  totalRetenciones: number;
  ajusteRedondeo: number;
  netoRecaudado: number;
  creadoEn?: string | null;
  confirmadoEn?: string | null;
  registradoPor?: string | null;
  observacion?: string | null;
  modoAplicacion: RetentionApplicationMode;
  facturas: { facturaId: string; numero: string; estadoFactura: string; valorAplicado: number }[];
  retenciones: PaymentWithholdingSnapshot[];
}
