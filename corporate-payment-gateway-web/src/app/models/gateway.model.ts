export interface InvoiceQr {
  contenido?: string;
  imagen?: string;
  estado?: string;
}

export interface GroupedInvoice {
  id: string;
  tipo: 'F1' | 'F20';
  consecutivo: string;
  clave_documento: string;
  valor_servicio: number;
  fecha_emision: string;
  fecha_vencimiento: string | null;
  /** Si la factura ya tiene su PDF oficial; sin él no se puede ver ni descargar. */
  pdf_disponible: boolean;
  estado_pago?: string;
  cufe: string | null;
  qr?: InvoiceQr | null;
  seleccionada?: boolean;
}

export interface ClientInvoiceGroup {
  nit: string;
  razon_social: string | null;
  cantidad_facturas: number;
  facturas: GroupedInvoice[];
  valor_total_servicio: number;
}

export type PaymentMethodType = 'PSE' | 'CARD' | 'BANCOLOMBIA_QR';

export interface PseBank {
  code: string;
  name: string;
  popular?: boolean;
}

export interface PaymentForm {
  method: PaymentMethodType;
  payerName: string;
  payerEmail: string;
  payerPhone: string;
  payerDocType: 'CC' | 'NIT' | 'CE' | 'PP';
  payerDocNumber: string;

  // PSE
  bankCode?: string;
  userType?: 'NATURAL' | 'JURIDICA';

  // Card
  cardNumber?: string;
  cardHolder?: string;
  cardExpiry?: string;
  cardCvv?: string;
  installments?: number;
}

export type TaxConceptType = 'RETEFUENTE' | 'RETEICA' | 'RETEIVA' | 'ESTAMPILLA';

/** Concepto del catálogo general vigente (GET /api/v1/impuestos/conceptos). */
export interface TaxCatalogConcept {
  id: string;
  nombre: string;
  tipo: TaxConceptType;
  /** Máximo que el cliente puede ingresar; el porcentaje lo escribe el cliente en cada pago. */
  porcentajeMaximo: number;
  baseMinima: number;
  version: number;
  vigenteDesde: string;
  vigenteHasta: string | null;
  municipio: string | null;
}

/** Evaluación de un concepto seleccionado; el backend decide si aplica. */
export interface WithholdingEvaluation {
  conceptoId: string;
  nombre: string;
  tipo: TaxConceptType;
  porcentaje: number;
  version: number;
  baseEvaluada: number;
  baseMinima: number;
  aplica: boolean;
  motivo: string | null;
  valorRetenido: number;
  /** Factura a la que pertenece (PER_INVOICE); null cuando la retención es consolidada. */
  facturaId: string | null;
}

/** Cómo eligió el cliente aplicar las retenciones; nunca se infiere. */
export type RetentionApplicationMode = 'PER_INVOICE' | 'CONSOLIDATED';

/** Factura dentro de la liquidación. En CONSOLIDATED no lleva retenciones ni neto propio. */
export interface SettledInvoice {
  id: string;
  tipo: string;
  numero: string;
  subtotalOficial: number;
  ivaOficial: number;
  totalOficial: number;
  valorLiquidado: number;
  retenciones: WithholdingEvaluation[];
  totalRetenciones: number | null;
  netoFactura: number | null;
}

/** Concepto marcado con el porcentaje que ingresó el cliente. */
export interface ConceptSelectionPayload {
  conceptId: string;
  percentage: number;
}

/** Selección de conceptos que se envía al backend, según el modo. */
export type RetentionSelectionPayload =
  | { applicationMode: 'CONSOLIDATED'; invoiceIds: string[]; selectedConcepts: ConceptSelectionPayload[] }
  | { applicationMode: 'PER_INVOICE'; invoices: { invoiceId: string; selectedConcepts: ConceptSelectionPayload[] }[] };

/** Configuración de retenciones que el cliente autorizó guardar para sus pagos futuros. */
export interface ClientRetentionConfig {
  nit: string;
  applicationMode: RetentionApplicationMode;
  autorizadoEn: string;
  conceptos: {
    conceptId: string;
    nombre: string | null;
    tipo: TaxConceptType | null;
    percentage: number;
    porcentajeMaximo: number | null;
    /** false si el concepto ya no está vigente o su máximo bajó: no se precarga. */
    utilizable: boolean;
  }[];
}

/** Liquidación oficial calculada por el backend (POST /api/v1/pagos/simular). */
export interface PaymentSettlement {
  nit: string;
  razonSocial: string | null;
  applicationMode: RetentionApplicationMode;
  facturas: SettledInvoice[];
  totalFacturas: number;
  baseGravable: number;
  totalIva: number;
  retenciones: WithholdingEvaluation[];
  totalRetenciones: number;
  netoAntesRedondeo: number;
  ajusteRedondeo: number;
  netoAPagar: number;
}

/** Pago PENDIENTE creado por el backend (POST /api/v1/pagos/checkout). */
export interface CheckoutResult {
  pagoId: string;
  referencia: string;
  estado: string;
  netoAPagar: number;
  liquidacion: PaymentSettlement;
}

export interface PaymentTransactionReceipt {
  transactionId: string;
  cus: string;
  authorizationCode: string;
  paymentDate: string;
  paymentMethod: PaymentMethodType;
  bankOrCardName: string;
  clientNit: string;
  clientBusinessName: string;
  invoicesPaid: {
    claveDocumento: string;
    consecutivo: string;
    tipo: string;
    valor: number;
    cufe?: string | null;
  }[];
  /** Neto efectivamente cobrado (netoAPagar del backend). */
  totalAmount: number;
  pagoId: string;
  /** Liquidación con la que se creó el checkout: total facturado, retenciones y ajuste. */
  settlement: PaymentSettlement;
  status: 'APROBADA' | 'RECHAZADA' | 'PENDIENTE';
  statusMessage: string;
}
