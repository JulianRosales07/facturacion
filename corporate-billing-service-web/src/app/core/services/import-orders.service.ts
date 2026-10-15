import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ApiService } from './api.service';
import {
  ImportHistoryItem,
  ImportResultResponse,
  NitRecord,
  FolderImportRequest,
  FolderConfigResponse
} from '../models/import-order.model';
import { environment } from '../../../environments/environment';

/**
 * Las operaciones que importan o consultan la carpeta propagan el error: un resultado
 * inventado haría creer que el archivo quedó cargado cuando el servidor no lo recibió.
 */
@Injectable({
  providedIn: 'root'
})
export class ImportOrdersService {
  private readonly api = inject(ApiService);
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiUrl;

  /**
   * Upload and process a single Excel (.xlsx, .xls) or flat file (.csv, .txt) from the browser
   */
  uploadFile(file: File, period?: string, origin = 'SPE', method = 'EXCEL'): Observable<ImportResultResponse> {
    const formData = new FormData();
    formData.append('archivo', file, file.name);
    if (period) formData.append('periodo', period);
    formData.append('origen', origin);
    formData.append('metodo', method);

    return this.http.post<ImportResultResponse>(this.resolveUrl('/importaciones/archivo'), formData);
  }

  /**
   * Check server folder path configuration, accessibility, and pending files list
   */
  getFolderConfig(path?: string): Observable<FolderConfigResponse> {
    const params: Record<string, string> = {};
    if (path) params['path'] = path;
    return this.api.get<FolderConfigResponse>('/importaciones/carpeta/config', { params });
  }

  /**
   * Request backend server to consume files directly from the specified server path
   */
  importServerFolder(folderPath?: string, periodCode?: string, origin = 'SPE'): Observable<ImportResultResponse> {
    const payload: FolderImportRequest = {
      folderPath: folderPath || undefined,
      periodCode,
      origin: origin as 'SPE'
    };
    return this.api.post<ImportResultResponse>('/importaciones/carpeta', payload);
  }

  /**
   * Get history of imports from database
   */
  getHistory(): Observable<ImportHistoryItem[]> {
    return this.api.get<ImportHistoryItem[]>('/importaciones/historial').pipe(
      catchError(() => of([]))
    );
  }

  /**
   * Get recent records grouped by NIT from database
   */
  getRecentRecords(): Observable<NitRecord[]> {
    return this.api.get<NitRecord[]>('/importaciones/registros-recientes').pipe(
      catchError(() => of([]))
    );
  }

  private resolveUrl(endpoint: string): string {
    if (endpoint.startsWith('http://') || endpoint.startsWith('https://')) {
      return endpoint;
    }
    const cleanEndpoint = endpoint.startsWith('/') ? endpoint.slice(1) : endpoint;
    const cleanBase = this.baseUrl.endsWith('/') ? this.baseUrl : `${this.baseUrl}/`;
    return `${cleanBase}${cleanEndpoint}`;
  }
}
