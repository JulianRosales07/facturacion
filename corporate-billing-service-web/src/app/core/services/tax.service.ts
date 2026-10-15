import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import {
  TaxConcept,
  TaxConceptValidity,
  TaxConceptValidityRequest,
  CreateNewVersionRequest
} from '../models/tax.model';

@Injectable({
  providedIn: 'root'
})
export class TaxService {
  private readonly api = inject(ApiService);

  listConcepts(): Observable<TaxConcept[]> {
    return this.api.get<TaxConcept[]>('/impuestos/conceptos');
  }

  listValidities(date?: string): Observable<TaxConceptValidity[]> {
    const params: Record<string, string> = {};
    if (date) params['fecha'] = date;
    return this.api.get<TaxConceptValidity[]>('/impuestos/vigencias', { params });
  }

  createValidity(request: TaxConceptValidityRequest): Observable<TaxConceptValidity> {
    return this.api.post<TaxConceptValidity>('/impuestos/vigencias', request);
  }

  createNewVersion(validityId: string, request: CreateNewVersionRequest): Observable<TaxConceptValidity> {
    return this.api.post<TaxConceptValidity>(`/impuestos/vigencias/${validityId}/nueva-version`, request);
  }

  updateValidity(validityId: string, request: TaxConceptValidityRequest): Observable<TaxConceptValidity> {
    return this.api.put<TaxConceptValidity>(`/impuestos/vigencias/${validityId}`, request);
  }

  deactivateValidity(validityId: string): Observable<void> {
    return this.api.delete<void>(`/impuestos/vigencias/${validityId}`);
  }
}
