export type TaxConceptCode = 'RETEFUENTE' | 'RETEICA' | 'RETEIVA' | 'ESTAMPILLA';
export type ServiceClassification = 'INTERNET' | 'SOFTWARE';

export interface TaxConcept {
  codigo: TaxConceptCode;
  nombre: string;
  descripcion?: string;
  activo: boolean;
}

export interface TaxConceptValidity {
  id: string;
  conceptCode: TaxConceptCode;
  nombre?: string;
  numeroVersion: number;
  classification?: ServiceClassification;
  municipality?: string;
  minimumBase: number;
  /** Máximo que el cliente puede ingresar al pagar; el % aplicado lo escribe el cliente. */
  porcentajeMaximo: number;
  validFrom: string;
  validTo?: string | null;
  active: boolean;
}

export interface TaxConceptValidityRequest {
  conceptCode: TaxConceptCode;
  nombre?: string;
  numeroVersion?: number;
  classification?: ServiceClassification;
  municipality?: string;
  minimumBase: number;
  /** Se envía como porcentaje máximo permitido. */
  percentage: number;
  validFrom: string;
  validTo?: string | null;
  active?: boolean;
}

export interface CreateNewVersionRequest {
  newMinimumBase?: number;
  newPercentage: number;
  effectiveFrom: string;
}
