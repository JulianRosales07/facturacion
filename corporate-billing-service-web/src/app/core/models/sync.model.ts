export interface SyncStatusEntity {
  id?: string;
  startedAt: string;
  finishedAt?: string;
  status: 'EXITOSA' | 'FALLIDA' | 'EN_PROGRESO' | 'CON_ADVERTENCIAS';
  detectedInvoices?: number;
  errors?: string;
  classifiedPdfs?: number;
}

export interface SyncStatusResponse {
  enEjecucion: boolean;
  ultimoCiclo?: SyncStatusEntity | null;
}

export interface OrderSyncResultDto {
  periodo: string;
  ordenesProcesadas: number;
  ordenesNuevas: number;
  ordenesActualizadas: number;
  errores: string[];
}
