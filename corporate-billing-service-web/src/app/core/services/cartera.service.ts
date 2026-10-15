import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import {
  CarteraSyncSummary,
  CarteraReconciliationResponse
} from '../models/cartera.model';

@Injectable({
  providedIn: 'root'
})
export class CarteraService {
  private readonly api = inject(ApiService);

  sincronizar(): Observable<CarteraSyncSummary> {
    return this.api.post<CarteraSyncSummary>('/cartera/sincronizar', {});
  }

  getReconciliation(): Observable<CarteraReconciliationResponse> {
    return this.api.get<CarteraReconciliationResponse>('/cartera/conciliacion');
  }

  getUnresolved(): Observable<any> {
    return this.api.get<any>('/cartera/pendientes-de-resolver');
  }
}
