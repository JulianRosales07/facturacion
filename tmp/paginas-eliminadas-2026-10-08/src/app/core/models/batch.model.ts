import { BillingCutType, BillingType } from './invoice.model';

export type ExportBatchStatus = 'GENERADO' | 'CONFIRMADO' | 'ERROR';

export interface LoteResponseDto {
  id: string;
  periodoId?: string;
  tipoFacturacion: BillingType;
  tipoCorte: string;
  nombreArchivo: string;
  totalFacturas: number;
  estado: ExportBatchStatus | string;
  generadoEn: string;
  confirmadoEn?: string | null;
}

export interface GenerarLoteRequest {
  periodo: string;
  tipoFacturacion: BillingType;
  tipoCorte?: BillingCutType;
}

export interface SiigoConfirmationRecord {
  facturaId?: string;
  numeroFactura?: string;
  cufe?: string;
  estadoDian?: string;
  observaciones?: string;
}

export interface ProcesarCarpetaResponseDto {
  archivosProcesados: number;
  facturasActualizadas: number;
  errores: string[];
}
