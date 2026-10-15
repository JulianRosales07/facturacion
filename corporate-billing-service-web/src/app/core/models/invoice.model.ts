export type BillingType = 'F1' | 'F20';
export type BillingCutType = 'ADVANCE' | 'ARREARS';
export type InvoiceStatus = 'BORRADOR' | 'CONSOLIDADA' | 'PAGADA' | 'ANULADA' | 'REEMPLAZADA';

export interface FacturaResumenDto {
  origenDocumento?: 'SIIGO' | 'INTERNO';
  id: string;
  numeroFactura: string;
  periodoCodigo: string;
  nit: string;
  razonSocial: string;
  nombreDisponible: boolean;
  tipoFacturacion: BillingType;
  tipoCorte: string;
  sedesCount: number;
  valorTotal: number;
  valorNotaCredito: number;
  saldoPendiente: number;
  netoPagar: number;
  estado: InvoiceStatus | string;
  fechaEmision: string;
  fechaVencimiento: string;
  esResidencial: boolean;
  version: number;
  vigente: boolean;
  saldoSiigo?: number | null;
  saldoCalculado?: number;
  estado_pago?: string;
  fecha_pago?: string | null;
  referencia_pago?: string | null;
  transaction_id?: string | null;
  valor_pagado?: number | null;
  proveedor_pago?: string | null;
}

export interface ClienteDto {
  nit: string;
  razonSocial: string;
  direccionFiscal: string;
  ciudad: string;
  telefono: string;
  emailFacturacion: string;
  estrato: string;
  vendedor: string;
  centroCosto: string;
}

export interface FacturaItemDto {
  item: number;
  codigo: string;
  descripcion: string;
  cant: number;
  und: string;
  valorUnitario: number;
  baseGravable: number;
  porcentajeIva: number;
  valorIva: number;
  valorTotal: number;
}

export interface FacturaTotalesDto {
  bruto: number;
  descuento: number;
  subtotal: number;
  iva: number;
  total: number;
}

export interface FacturaRetencionesDto {
  retefuente: number;
  reteica: number;
  reteiva: number;
  estampillas: number;
  totalRetenciones: number;
  netoPagar: number;
}

export interface FacturaDetalleDto {
  origenDocumento?: 'SIIGO' | 'INTERNO';
  id: string;
  numeroFactura: string;
  periodoCodigo: string;
  tipoFacturacion: BillingType;
  tipoCorte: string;
  estado: InvoiceStatus | string;
  fechaEmision: string;
  fechaVencimiento: string;
  cufe?: string;
  rutaPdf?: string;
  cliente: ClienteDto;
  items: FacturaItemDto[];
  totales: FacturaTotalesDto;
  retenciones: FacturaRetencionesDto;
  formaPago: string;
  observaciones: string;
  estado_pago?: string;
  fecha_pago?: string | null;
  referencia_pago?: string | null;
  transaction_id?: string | null;
  valor_pagado?: number | null;
  proveedor_pago?: string | null;
}

export interface FacturaVersionDto {
  id: string;
  version: number;
  vigente: boolean;
  numeroFactura: string;
  estado: string;
  valorTotal: number;
  diferenciaConVersionAnterior?: number;
  cantidadItems: number;
  cufe?: string;
  rutaPdf?: string;
  fechaEmision: string;
  motivoRegeneracion?: string;
  versionAnteriorId?: string;
  reemplazadaPorId?: string;
  reemplazadaEn?: string;
  creadoPor?: string;
  regeneradoPor?: string;
}

export interface GenerarFacturaRequest {
  periodoCodigo: string;
  tipoFacturacion?: BillingType;
  tipoCorte?: BillingCutType;
  nit?: string;
}

export interface GenerarFacturasResponse {
  generadas: number;
  facturas: FacturaResumenDto[];
}

export interface RegenerarFacturaRequest {
  motivo: string;
  confirmarCufeTransmitido?: boolean;
}

export interface InvoiceFilters {
  periodo?: string;
  nit?: string;
  estado?: string;
  nombre?: string;
  razonSocial?: string;
  numeroFactura?: string;
  fechaDesde?: string;
  fechaHasta?: string;
  incluirResidenciales?: boolean;
  soloConSaldo?: boolean;
  page?: number;
  size?: number;
}

export interface CorporateSiteDetail {
  id?: string;
  codigoOrden: string;
  nombreSede: string;
  direccion: string;
  valorMensual: number;
  estado: string;
  nic?: string;
  anchoBanda?: string;
}

export interface ClientNoveltyDetail {
  id: string;
  tipoNovedad: 'ALTA' | 'BAJA' | 'CAMBIO_PLAN' | 'CAMBIO_SERVICIO' | 'SUSPENSION' | 'REACTIVACION' | 'TRASLADO_DIRECCION' | 'CUOTA_INSTALACION';
  sedeNombre: string;
  codigoOrden: string;
  fechaEfectiva: string;
  diasProrrateo?: number;
  valorOriginal?: number;
  valorAjuste: number;
  tipoImpacto: 'SUMA' | 'RESTA' | 'INFORMATIVO';
  observacion: string;
  estado: 'PENDIENTE_FACTURAR' | 'CONSUMIDA' | 'EN_REVISION';
  categoria?: 'NUEVA_ADQUISICION' | 'CAMBIO_SERVICIO' | 'ADICION' | 'RETIRO' | 'AJUSTE_PERIODO_ANTERIOR' | 'OTRO' | string;
  /** Trazabilidad: valor que entra con la novedad, su estado frente a la facturación y dónde se cobró. */
  valorNuevo?: number | null;
  estadoProcesamiento?: 'PENDIENTE' | 'APLICADA' | 'PENDIENTE_AJUSTE' | 'AJUSTE_APLICADO' | null;
  detalleProcesamiento?: string | null;
  periodoNovedad?: string | null;
  facturaId?: string | null;
  numeroFactura?: string | null;
}

export interface ClientNoveltiesSummary {
  periodo: string;
  totalNovedades: number;
  baseMensual: number;
  ajusteNovedades: number;
  cuotasInstalacion: number;
  totalEstimadoFactura: number;
  novedades: ClientNoveltyDetail[];
}

export interface CorporateClientOption {
  id?: string;
  nit: string;
  razonSocial: string;
  branchesCount?: number;
  tipoFacturacion?: BillingType;
  municipio?: string;
  direccionFiscal?: string;
  emailFacturacion?: string;
  telefono?: string;
  appliesWithholding?: boolean;
  totalMonthlyValue?: number;
  services?: string[];
  sedes?: CorporateSiteDetail[];
  novedadesSummary?: ClientNoveltiesSummary;
}



/** GET /facturas/{id}/detalle-cobros: la factura agrupada como la ve el cliente. Valores antes de IVA. */
export interface LineaCobroDto {
  descripcion: string;
  fechaInicial: string | null;
  fechaFinal: string | null;
  valor: number;
  novedadId: string | null;
  /** Subtítulo dentro de Otros conceptos (Novedades del servicio, Cuotas de instalación, …); null en cargos fijos. */
  categoria: string | null;
}
export interface BloqueCobrosDto {
  lineas: LineaCobroDto[];
  subtotal: number;
  impuestos: number;
  total: number;
}
export interface DetalleCobrosDto {
  deudaAnterior: { cargosAnteriores: number; pagosRecibidos: number; saldo: number; facturas: number };
  cargosFijos: BloqueCobrosDto;
  otrosConceptos: BloqueCobrosDto;
  totalFactura: number;
  totalAPagar: number;
}
