import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import {
  LoteResponseDto,
  GenerarLoteRequest,
  SiigoConfirmationRecord,
  ProcesarCarpetaResponseDto
} from '../models/batch.model';

@Injectable({
  providedIn: 'root'
})
export class BatchService {
  private readonly api = inject(ApiService);

  generarLote(request: GenerarLoteRequest): Observable<LoteResponseDto> {
    return this.api.post<LoteResponseDto>('/lotes/generar', request);
  }

  obtenerLote(id: string): Observable<LoteResponseDto> {
    return this.api.get<LoteResponseDto>(`/lotes/${id}`);
  }

  listarLotes(): Observable<LoteResponseDto[]> {
    return this.api.get<LoteResponseDto[]>('/lotes');
  }

  confirmarLote(id: string, confirmaciones?: SiigoConfirmationRecord[]): Observable<LoteResponseDto> {
    return this.api.post<LoteResponseDto>(`/lotes/${id}/confirmar`, confirmaciones || []);
  }

  procesarCarpeta(): Observable<ProcesarCarpetaResponseDto> {
    return this.api.post<ProcesarCarpetaResponseDto>('/lotes/procesar-carpeta', {});
  }

  getArchivoLoteUrl(id: string): string {
    return `/api/v1/lotes/${id}/archivo`;
  }
}
