export interface ItemNotaCreditoResponseDto {
  id: string;
  itemFacturableId?: string;
  descripcion: string;
  cantidad: number;
  valorUnitario: number;
  valorDescuento: number;
  porcentajeIva: number;
  valorIva: number;
  valorNeto: number;
}

export interface NotaCreditoResponseDto {
  id: string;
  facturaId: string;
  tipo: string;
  numeroDocumento: string;
  motivo: string;
  valorBruto: number;
  valorDescuento: number;
  valorSubtotal: number;
  valorIva: number;
  valorNeto: number;
  estado: string;
  creadoEn: string;
  items: ItemNotaCreditoResponseDto[];
}

export interface ItemNotaCreditoRequest {
  itemFacturableId?: string;
  descripcion: string;
  cantidad: number;
  valorUnitario?: number;
  valorDescuento?: number;
  porcentajeIva?: number;
}

export interface EmitirNotaCreditoRequest {
  motivo: string;
  numeroDocumento?: string;
  items: ItemNotaCreditoRequest[];
}
