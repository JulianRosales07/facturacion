export interface FacturaSpPagosDto {
  id: string;
  numeroFactura: string;
  prefijo: string;
  numeroCompleto: string;
  tipoFacturacion: string;
  periodo: string;
  cufe: string | null;
  estado: string;
  pagable: boolean;
  cliente: {
    nit: string;
    razonSocial: string;
    direccionFiscal: string;
    emailFacturacion: string;
    telefono: string | null;
  };
  fechas: {
    fechaEmision: string;
    fechaVencimiento: string;
    vencida: boolean;
    fechaImportacion?: string | null;
    fechaDisponiblePago?: string | null;
    fechaPago?: string | null;
  };
  importes: {
    subtotal: number;
    descuento: number;
    iva: number;
    totalOficial: number;
    totalNotasCredito: number;
    totalPagado: number;
    saldoPendiente: number;
    fuenteSaldo: string;
    carteraAnteriorPendiente?: number;
    saldoTotalPorCobrar?: number;
  };
  retenciones: {
    retefuente: number;
    reteica: number;
    reteiva: number;
    estampillas: number;
    totalRetenciones: number;
    netoConRetenciones: number;
  };
  items: Array<{
    item: number;
    codigo: string;
    descripcion: string;
    cantidad: number;
    unidad: string;
    valorUnitario: number;
    baseGravable: number;
    porcentajeIva: number;
    valorIva: number;
    valorTotal: number;
  }>;
  enlaces: {
    tienePdf: boolean;
    pdfUrl: string;
    representacionGraficaUrl: string;
  };
  formaPago: string;
  observaciones: string;
  cartera?: {
    valorFacturaActual: number;
    saldoFacturaActual: number;
    carteraAnteriorPendiente: number;
    saldoTotalPorCobrar: number;
    totalObligacionesPendientes: number;
    obligacionesAnteriores: Array<{
      id: string;
      tipo: string;
      numeroFactura: string;
      periodo: string;
      fechaEmision: string;
      fechaVencimiento: string;
      valorFactura: number;
      valorPagado: number;
      saldoPendiente: number;
      estado: string;
      cufe: string | null;
    }>;
  } | null;
}
