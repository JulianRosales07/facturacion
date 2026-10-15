/** Canal desde el que se diligenció una retención. */
export type SourceChannel = 'PAYMENT_GATEWAY' | 'ADMIN_WEB' | 'MANUAL_PAYMENT' | 'API';

export const CHANNEL_LABELS: Record<SourceChannel, string> = {
  PAYMENT_GATEWAY: 'Pasarela de pagos',
  ADMIN_WEB: 'Panel administrativo',
  MANUAL_PAYMENT: 'Registro manual',
  API: 'API'
};

export const SELECTION_STATUS_LABELS: Record<string, string> = {
  SELECTED: 'Seleccionada (pago pendiente)',
  NOT_APPLICABLE: 'No aplica',
  APPLIED: 'Aplicada',
  CANCELLED: 'Anulada'
};

export const EVENT_LABELS: Record<string, string> = {
  SELECTED: 'Seleccionó',
  UPDATED: 'Cambió el porcentaje',
  REMOVED: 'Quitó',
  NOT_APPLICABLE: 'No aplica',
  APPLIED: 'Aplicada',
  CANCELLED: 'Anulada'
};

/** Quién diligenció: USER (panel), CLIENT (pasarela, sin usuario), SYSTEM o UNKNOWN (histórico sin registro). */
export interface AuditActor {
  type: 'USER' | 'CLIENT' | 'SYSTEM' | 'UNKNOWN';
  userId: string | null;
  username: string | null;
  name: string | null;
  nit: string | null;
  sessionId: string | null;
  label: string;
}

/** Snapshot de una retención del pago tal como quedó (las horas vienen en -05:00, America/Bogota). */
export interface WithholdingAuditRow {
  id: string;
  paymentId: string;
  paymentStatus: string;
  clientNit: string | null;
  clientName: string | null;
  invoiceId: string | null;
  invoiceNumbers: string[];
  conceptValidityId: string | null;
  conceptCode: string;
  conceptName: string;
  conceptVersion: number;
  validFrom: string | null;
  validTo: string | null;
  enteredPercentage: number;
  appliedPercentage: number;
  maxPercentage: number | null;
  calculationBase: number;
  minimumBase: number;
  withheldAmount: number;
  applicable: boolean;
  nonApplicableReason: string | null;
  adjustmentReason: string | null;
  selectionStatus: 'SELECTED' | 'NOT_APPLICABLE' | 'APPLIED' | 'CANCELLED';
  selectedAt: string;
  appliedAt: string | null;
  sourceChannel: SourceChannel | null;
  selectedBy: AuditActor;
  ipAddress: string | null;
  userAgent: string | null;
}

/** Acción de la persona durante el proceso (historial; distinto del snapshot final). */
export interface WithholdingAuditEvent {
  id: string;
  eventType: 'SELECTED' | 'UPDATED' | 'REMOVED' | 'NOT_APPLICABLE' | 'APPLIED' | 'CANCELLED';
  sessionId: string;
  paymentId: string | null;
  invoiceId: string | null;
  conceptCode: string;
  conceptName: string | null;
  oldPercentage: number | null;
  newPercentage: number | null;
  occurredAt: string;
  sourceChannel: SourceChannel;
  actor: AuditActor;
  ipAddress: string | null;
  userAgent: string | null;
}

export interface PaymentWithholdingAudit {
  paymentId: string;
  status: string;
  applicationMode: string | null;
  clientNit: string | null;
  clientName: string | null;
  invoiceNumbers: string[];
  createdAt: string | null;
  confirmedAt: string | null;
  audit: WithholdingAuditRow[];
  events: WithholdingAuditEvent[];
}

export interface WithholdingAuditFilter {
  nit?: string;
  invoice?: string;
  paymentId?: string;
  concept?: string;
  user?: string;
  channel?: SourceChannel | '';
  from?: string;
  to?: string;
}

export interface WithholdingAuditPage {
  items: WithholdingAuditRow[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}
