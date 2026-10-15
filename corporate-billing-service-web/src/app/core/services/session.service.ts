import { Injectable, signal } from '@angular/core';

export interface CompanyContext {
  id: string;
  name: string;
  taxId: string;
}

@Injectable({
  providedIn: 'root'
})
export class SessionService {
  private readonly activeCompanySignal = signal<CompanyContext>({
    id: 'corp-001',
    name: 'Corporación Demo S.A.S.',
    taxId: '900.123.456-7'
  });

  readonly activeCompany = this.activeCompanySignal.asReadonly();

  setActiveCompany(company: CompanyContext): void {
    this.activeCompanySignal.set(company);
  }
}
