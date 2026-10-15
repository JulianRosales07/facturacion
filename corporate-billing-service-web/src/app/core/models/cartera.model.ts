export interface CarteraDiscrepancy {
  invoiceId?: string;
  invoiceNumber: string;
  clientNit: string;
  saldoSiigo?: number;
  saldoCalculado?: number;
  diferencia?: number;
}

export interface CarteraSnapshotResult {
  snapshotId: string;
  archivo: string;
  fechaCorte: string;
  filasTotales: number;
  filasAplicadas: number;
  filasSinFactura: number;
  filasConError: number;
  facturasAusentesDelCorte: number;
  diferenciasDeSaldo: number;
}

export interface CarteraSyncSummary {
  archivosEncontrados: number;
  archivosIngeridos: number;
  archivosOmitidosPorDuplicado: number;
  filasAplicadas: number;
  cortes: CarteraSnapshotResult[];
}

export interface CarteraReconciliationResponse {
  fechaCorte?: string;
  totalDiscrepancias: number;
  discrepancias: CarteraDiscrepancy[];
}
