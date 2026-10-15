export interface ImportHistoryItem {
  id: string;
  date: string;
  method: string;
  fileName: string;
  result: string;
  status: 'success' | 'warning' | 'error';
  totalRecords?: number;
  errorRecords?: number;
  receivedAt?: string;
}

export interface NitRecord {
  nit: string;
  clientName: string;
  branchesCount: number;
  totalAmount: string;
  status: string;
  services: string[];
}

export interface ImportResultResponse {
  id: string;
  fileName: string;
  method: string;
  origin: string;
  status: string;
  totalRecords: number;
  errorRecords: number;
  receivedAt: string;
  processedAt: string;
  records: NitRecord[];
  messages: string[];
}

export interface FolderImportRequest {
  folderPath?: string;
  periodCode?: string;
  origin?: 'SPE' | 'MEGA_RED' | 'ENLACES';
}

export interface FolderConfigResponse {
  configuredPath: string;
  absolutePath: string;
  accessible: boolean;
  filesCount: number;
  pendingFiles: string[];
}
