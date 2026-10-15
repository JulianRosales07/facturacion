import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { NotaCreditoResponseDto } from '../models/credit-note.model';

@Injectable({
  providedIn: 'root'
})
export class CreditNoteService {
  private readonly api = inject(ApiService);

  getCreditNoteById(id: string): Observable<NotaCreditoResponseDto> {
    return this.api.get<NotaCreditoResponseDto>(`/notas-credito/${id}`);
  }
}
