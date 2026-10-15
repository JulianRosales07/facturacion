import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import {
  SyncStatusResponse,
  SyncStatusEntity,
  OrderSyncResultDto
} from '../models/sync.model';

@Injectable({
  providedIn: 'root'
})
export class SyncService {
  private readonly api = inject(ApiService);

  getSyncStatus(): Observable<SyncStatusResponse> {
    return this.api.get<SyncStatusResponse>('/sincronizacion/estado');
  }

  triggerManualSync(): Observable<SyncStatusEntity> {
    return this.api.post<SyncStatusEntity>('/sincronizacion/ahora', {});
  }

  syncPeriod(periodo?: string): Observable<OrderSyncResultDto> {
    const params: Record<string, string> = {};
    if (periodo) params['periodo'] = periodo;
    return this.api.post<OrderSyncResultDto>('/sincronizacion', {}, { params });
  }
}
