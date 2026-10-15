export interface NicBillingItem {
  nic: string;
  clienteNit: string;
  razonSocial: string;
  sedesCount: number;
  subtotal: number;
  iva: number;
  total: number;
}

export interface NicBillingReportResponse {
  periodo: string;
  totalNicCount: number;
  granTotal: number;
  items: NicBillingItem[];
}
