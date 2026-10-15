import { Component, OnInit, DestroyRef, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { Subscription, debounceTime } from 'rxjs';
import { AdminRealtimeService } from '../../../../core/services/admin-realtime.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { IncomingInvoiceService } from '../../../../core/services/incoming-invoice.service';
import { InvoiceService } from '../../../../core/services/invoice.service';
import { PaymentService } from '../../../../core/services/payment.service';
import { FileDownloadService } from '../../../../core/services/file-download.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { ClientInvoiceGroup, IncomingFilters, IncomingInvoice, InvoiceListView, PageResult } from '../../../../core/models/incoming-invoice.model';
import { FacturaDetalleDto } from '../../../../core/models/invoice.model';
import { PaymentDetail } from '../../../../core/models/payment.model';
import { CopCurrencyPipe } from '../../../../shared/pipes/cop-currency.pipe';
import { PaymentWithholdingsTableComponent } from '../../../../shared/components/payment-withholdings-table/payment-withholdings-table.component';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { WithholdingAuditPanelComponent } from '../../../../shared/components/withholding-audit-panel/withholding-audit-panel.component';
import { AppDatePipe } from '../../../../shared/pipes/app-date.pipe';
import { DatePickerComponent } from '../../../../shared/components/calendar/date-picker.component';
import { MatIconModule } from '@angular/material/icon';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ApiService } from '../../../../core/services/api.service';

@Component({
  selector: 'app-invoice-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, CopCurrencyPipe, AppDatePipe, DatePickerComponent, MatIconModule, PaymentWithholdingsTableComponent, HasPermissionDirective, WithholdingAuditPanelComponent],
  templateUrl: './invoice-list.component.html',
  styleUrls: ['../../../../shared/styles/received-pages.scss', './invoice-list-grouped.scss']
})
export class InvoiceListComponent implements OnInit {
  private readonly api=inject(IncomingInvoiceService);
  private readonly rawApi=inject(ApiService);
  private readonly sanitizer=inject(DomSanitizer);
  private readonly importer=inject(InvoiceService);
  private readonly paymentService=inject(PaymentService);
  private readonly downloads=inject(FileDownloadService);
  private readonly notifications=inject(NotificationService);
  private readonly route=inject(ActivatedRoute);
  private readonly router=inject(Router);
  private readonly destroy=inject(DestroyRef);
  private readonly realtime=inject(AdminRealtimeService);
  private request?: Subscription;
  private pdfObjectUrl = '';

  /** Vista por defecto: un objeto por NIT. 'factura' conserva la tabla anterior (una fila por documento). */
  readonly view=signal<InvoiceListView>('nit');
  readonly result=signal<PageResult<IncomingInvoice> | null>(null);
  readonly grouped=signal<PageResult<ClientInvoiceGroup> | null>(null);
  readonly expanded=signal<ReadonlySet<string>>(new Set());
  readonly selectedGroup=signal<ClientInvoiceGroup | null>(null);
  readonly activeModalTab=signal<'servicios' | 'factura' | 'pagos'>('factura');
  readonly loading=signal(false);
  readonly importing=signal(false);
  readonly error=signal('');
  readonly pageSizes=[10,25,50,100];
  private readonly defaultPageSize=10;
  filters: IncomingFilters={page:0,size:this.defaultPageSize};

  // Factura seleccionada para la pestaña "Control y trazabilidad / Liquidación"
  readonly selectedInvoiceId = signal<string | null>(null);
  readonly selectedInvoiceDetail = signal<FacturaDetalleDto | null>(null);
  readonly selectedInvoicePayments = signal<PaymentDetail[]>([]);
  readonly loadingInvoiceDetail = signal(false);
  readonly detailError = signal('');
  readonly paymentsError = signal('');

  // Visor integrado de PDF para evitar redirección y duplicidad de navegación
  readonly selectedPdfInvoice = signal<any | null>(null);
  readonly safePdfUrl = signal<SafeResourceUrl | null>(null);
  readonly pdfLoading = signal(false);
  readonly pdfError = signal('');

  readonly summaryStats = computed(() => {
    if (this.view() === 'nit') {
      const g = this.grouped();
      if (!g || !g.items.length) return null;
      const totalFacturado = g.items.reduce((acc, item) => acc + (item.valor_total_servicio || 0), 0);
      const totalPagado = g.items.reduce((acc, item) => acc + (item.valor_pagado || 0), 0);
      const totalPendiente = g.items.reduce((acc, item) => {
        const val = (item.valor_pendiente !== undefined && item.valor_pendiente !== null) 
          ? item.valor_pendiente 
          : item.valor_total_servicio;
        return acc + (val || 0);
      }, 0);
      const totalFacturas = g.items.reduce((acc, item) => acc + (item.cantidad_facturas || 0), 0);
      const pendingPdfs = g.items.reduce((acc, item) => acc + this.pendingPdfs(item), 0);
      return {
        totalFacturado,
        totalPagado,
        totalPendiente,
        totalFacturas,
        totalClientes: g.totalElements,
        pendingPdfs,
        totalItemsCount: g.items.length
      };
    } else {
      const r = this.result();
      if (!r || !r.items.length) return null;
      const totalFacturado = r.items.reduce((acc, item) => acc + (item.total || 0), 0);
      const totalPagado = r.items.filter(i => i.estado_pago === 'PAGADA').reduce((acc, item) => acc + (item.total || 0), 0);
      const totalPendiente = r.items.filter(i => i.estado_pago !== 'PAGADA' && i.estado_pago !== 'ANULADA').reduce((acc, item) => acc + (item.total || 0), 0);
      const pendingPdfs = r.items.filter(i => !i.pdfAvailable).length;
      return {
        totalFacturado,
        totalPagado,
        totalPendiente,
        totalFacturas: r.totalElements,
        totalClientes: null,
        pendingPdfs,
        totalItemsCount: r.items.length
      };
    }
  });

  ngOnInit(): void {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(params => {
      this.view.set(params.get('vista')==='factura' ? 'factura' : 'nit');
      this.filters={search:params.get('search')||'',number:params.get('number')||'',type:params.get('type')||'',
        period:params.get('period')||'',from:params.get('from')||'',to:params.get('to')||'',pdfAvailable:params.get('pdfAvailable')||'',
        estadoPago:params.get('estadoPago')||'',
        origen:params.get('origen')||'',
        page:Math.max(0,Number(params.get('page'))||0),size:this.parsePageSize(params.get('size'))};
      this.load();
    });
    this.destroy.onDestroy(() => {
      this.request?.unsubscribe();
      this.releasePdfUrl();
    });
    // Tiempo real: el aviso solo dispara una nueva consulta REST (estados y totales los calcula el backend).
    this.realtime.refreshSignals().pipe(debounceTime(400),takeUntilDestroyed(this.destroy)).subscribe(sig => {
      this.refreshSilently();
      const currentInvId = this.selectedInvoiceId();
      if (currentInvId && this.activeModalTab() === 'pagos' && AdminRealtimeService.concernsInvoice(sig, currentInvId)) {
        // Recarga en segundo plano: no vacía el modal ni mueve la vista mientras llega la respuesta.
        this.selectInvoiceForDetail(currentInvId, true);
      }
    });
  }

  openPdfModal(inv: any): void {
    this.releasePdfUrl();
    this.selectedPdfInvoice.set(inv);
    this.safePdfUrl.set(null);
    this.pdfLoading.set(true);
    this.pdfError.set('');
    this.rawApi.getBlob(`/facturas/${inv.id}/pdf`).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: blob => {
        this.pdfLoading.set(false);
        if (!blob.type.includes('application/pdf')) {
          this.pdfError.set('El servidor no devolvió un archivo PDF válido.');
          return;
        }
        this.pdfObjectUrl = URL.createObjectURL(blob);
        this.safePdfUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(this.pdfObjectUrl));
      },
      error: err => {
        this.pdfLoading.set(false);
        this.pdfError.set(err.status === 404 ? 'El PDF oficial aún no está disponible.' : 'No se pudo cargar el archivo PDF.');
      }
    });
  }

  closePdfModal(): void {
    this.releasePdfUrl();
    this.selectedPdfInvoice.set(null);
    this.safePdfUrl.set(null);
    this.pdfLoading.set(false);
    this.pdfError.set('');
  }

  downloadCurrentPdf(): void {
    const inv = this.selectedPdfInvoice();
    if (!inv) return;
    const name = inv.consecutivo ? `${inv.tipo}-${inv.consecutivo}` : (inv.invoiceNumber ? `${inv.billingType}-${inv.invoiceNumber}` : (inv.numeroFactura ? `${inv.tipoFacturacion}-${inv.numeroFactura}` : `factura-${inv.id}`));
    this.downloads.download(`/facturas/${inv.id}/pdf`, `${name}.pdf`);
  }

  private releasePdfUrl(): void {
    if (this.pdfObjectUrl) {
      URL.revokeObjectURL(this.pdfObjectUrl);
      this.pdfObjectUrl = '';
    }
  }
  load(): void {
    this.request?.unsubscribe(); this.loading.set(true); this.error.set('');
    const onError=(err: any)=>{this.result.set(null);this.grouped.set(null);this.loading.set(false);this.error.set(err?.error?.mensaje||'No se pudo consultar las facturas.');};
    if(this.view()==='nit'){
      this.request=this.api.listByNit(this.filters).subscribe({next:data=>{
        this.grouped.set(data);this.result.set(null);this.loading.set(false);
        // Si la búsqueda deja un único cliente, se abre directamente su detalle.
        this.expanded.set(new Set(data.items.length===1 ? [data.items[0].nit] : []));
      },error:onError});
    } else {
      this.request=this.api.list(this.filters).subscribe({next:data=>{this.result.set(data);this.grouped.set(null);this.loading.set(false);},error:onError});
    }
  }
  /** Igual que load() pero sin spinner ni cambiar lo expandido: el usuario ve el cambio sin perder su contexto. */
  refreshSilently(): void {
    if(this.loading()) return;
    if(this.view()==='nit'){
      this.api.listByNit(this.filters).subscribe({next:data=>{
        this.grouped.set(data);
        const open=this.selectedGroup();
        if(open){this.selectedGroup.set(data.items.find(g=>g.nit===open.nit) ?? open);}
      }});
    } else {
      this.api.list(this.filters).subscribe({next:data=>this.result.set(data)});
    }
  }
  search(page=0): void {
    if(this.filters.from && this.filters.to && this.filters.from>this.filters.to){this.error.set('La fecha inicial no puede superar la final.');return;}
    this.filters.page=page;
    const vista=this.view()==='factura' ? 'factura' : undefined;
    void this.router.navigate([], {relativeTo:this.route,queryParams:{...this.filters,size:this.filters.size===this.defaultPageSize ? undefined : this.filters.size,vista}}).then(changed=>{if(!changed)this.load();});
  }
  setView(view: InvoiceListView): void {
    if(this.view()===view) return;
    this.view.set(view);
    this.search(0); // la paginación cambia de unidad (NIT vs factura): se vuelve a la primera página
  }
  toggle(nit: string): void {
    const next=new Set(this.expanded());
    if(next.has(nit)) next.delete(nit); else next.add(nit);
    this.expanded.set(next);
  }
  isExpanded(nit: string): boolean { return this.expanded().has(nit); }
  pendingPdfs(group: ClientInvoiceGroup): number { return group.facturas.filter(f=>!f.pdf_disponible).length; }
  reset(): void { this.filters={page:0,size:this.defaultPageSize,estadoPago:''}; this.search(); }
  changePageSize(size: number): void { this.filters.size=size; this.search(0); }
  private parsePageSize(value: string | null): number {
    const size=Number(value);
    return this.pageSizes.includes(size) ? size : this.defaultPageSize;
  }
  /** Páginas (base 0) a mostrar: primera, última y vecinas de la actual; null marca un salto (…). */
  pageNumbers(current: number, total: number): (number | null)[] {
    if(total<=7) return Array.from({length:total},(_,i)=>i);
    const pages=new Set([0,total-1,current-1,current,current+1].filter(p=>p>=0 && p<total));
    if(current<=3) [1,2,3,4].forEach(p=>pages.add(p));
    if(current>=total-4) [total-5,total-4,total-3,total-2].forEach(p=>pages.add(p));
    const sorted=[...pages].sort((a,b)=>a-b);
    return sorted.flatMap((p,i)=>i>0 && p-sorted[i-1]>1 ? [null,p] : [p]);
  }
  /** El importador guarda "NIT x (razón social pendiente)" cuando Siigo no trae el nombre. */
  hasBusinessName(group: ClientInvoiceGroup): boolean {
    return !!group.razon_social && !group.razon_social.endsWith('(razón social pendiente)');
  }
  pendingAmount(group: ClientInvoiceGroup): number {
    return (group.valor_pendiente !== undefined && group.valor_pendiente !== null) ? group.valor_pendiente : group.valor_total_servicio;
  }
  download(invoice: IncomingInvoice): void { this.downloads.download(`/facturas/${invoice.id}/pdf`,`${invoice.billingType}-${invoice.invoiceNumber}.pdf`); }
  downloadGrouped(invoice: { id: string; tipo: string; consecutivo: string }): void {
    this.downloads.download(`/facturas/${invoice.id}/pdf`,`${invoice.tipo}-${invoice.consecutivo}.pdf`);
  }
  /** Por omisión abre las facturas por servicio: cada una con su PDF oficial, no el total consolidado. */
  openClientModal(group: ClientInvoiceGroup, tab: 'servicios' | 'factura' | 'pagos' = 'servicios', targetInvoiceId?: string): void {
    this.selectedGroup.set(group);
    this.activeModalTab.set(tab);
    const initialInvoiceId = targetInvoiceId || (group.facturas.length > 0 ? group.facturas[0].id : null);
    if (initialInvoiceId) {
      this.selectInvoiceForDetail(initialInvoiceId);
    }
  }

  closeClientModal(): void {
    this.selectedGroup.set(null);
    this.selectedInvoiceId.set(null);
    this.selectedInvoiceDetail.set(null);
    this.selectedInvoicePayments.set([]);
  }

  setModalTab(tab: 'servicios' | 'factura' | 'pagos'): void {
    this.activeModalTab.set(tab);
    if (tab === 'pagos') {
      const group = this.selectedGroup();
      if (group && group.facturas.length > 0 && !this.selectedInvoiceDetail()) {
        const invId = this.selectedInvoiceId() || group.facturas[0].id;
        this.selectInvoiceForDetail(invId);
      }
    }
  }

  /**
   * Carga el detalle y los pagos de la factura. Con {@code silent} (avisos en tiempo real) conserva lo
   * que se está viendo y solo lo reemplaza cuando llega la respuesta; un error en segundo plano no lo borra.
   */
  /** Pagos con la auditoría de retenciones desplegada en el modal. */
  readonly openAudits = signal<Set<string>>(new Set());

  toggleAudit(paymentId: string): void {
    this.openAudits.update(open => {
      const next = new Set(open);
      if (next.has(paymentId)) next.delete(paymentId); else next.add(paymentId);
      return next;
    });
  }

  selectInvoiceForDetail(invoiceId: string, silent = false): void {
    this.selectedInvoiceId.set(invoiceId);
    if (!silent) {
      this.loadingInvoiceDetail.set(true);
      this.detailError.set('');
      this.paymentsError.set('');
      this.selectedInvoiceDetail.set(null);
      this.selectedInvoicePayments.set([]);
    }

    this.importer.getInvoiceById(invoiceId).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: inv => {
        if (this.selectedInvoiceId() !== invoiceId) return;
        this.selectedInvoiceDetail.set(inv);
        this.loadingInvoiceDetail.set(false);
      },
      error: err => {
        if (silent || this.selectedInvoiceId() !== invoiceId) return;
        this.loadingInvoiceDetail.set(false);
        this.detailError.set(err?.error?.mensaje || 'No se pudo consultar el detalle de la factura.');
      }
    });

    this.paymentService.getPaymentsForInvoice(invoiceId).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: list => {
        if (this.selectedInvoiceId() !== invoiceId) return;
        this.selectedInvoicePayments.set(list);
      },
      error: err => {
        if (silent || this.selectedInvoiceId() !== invoiceId) return;
        this.selectedInvoicePayments.set([]);
        this.paymentsError.set(err?.error?.mensaje || 'No se pudieron consultar los pagos de la factura.');
      }
    });
  }

  openInvoiceDetailModal(inv: IncomingInvoice): void {
    const syntheticGroup: ClientInvoiceGroup = {
      nit: inv.clientNit,
      razon_social: inv.businessName,
      cantidad_facturas: 1,
      valor_total_servicio: inv.total,
      valor_pagado: inv.estado_pago === 'PAGADA' ? inv.total : 0,
      valor_pendiente: inv.estado_pago === 'PAGADA' ? 0 : inv.total,
      facturas: [{
        id: inv.id,
        tipo: inv.billingType,
        consecutivo: inv.invoiceNumber,
        clave_documento: `${inv.billingType}-${inv.invoiceNumber}`,
        valor_servicio: inv.total,
        estado_pago: inv.estado_pago,
        fecha_emision: inv.issueDate,
        fecha_vencimiento: inv.dueDate,
        pdf_disponible: inv.pdfAvailable,
        cufe: ''
      }]
    };
    this.openClientModal(syntheticGroup, 'pagos', inv.id);
  }

  openGroupInvoiceDetail(group: ClientInvoiceGroup, invoiceId: string): void {
    this.openClientModal(group, 'pagos', invoiceId);
  }

  downloadInvoiceDetail(inv: FacturaDetalleDto): void {
    this.downloads.download(`/facturas/${inv.id}/pdf`, `${inv.tipoFacturacion}-${inv.numeroFactura}.pdf`);
  }

  downloadConsolidatedPdf(group: ClientInvoiceGroup): void {
    this.downloads.download(
      `/facturas/recibidas/por-nit/${encodeURIComponent(group.nit)}/pdf`,
      `factura-consolidada-NIT-${group.nit}.pdf`
    );
  }
  printConsolidated(): void {
    window.print();
  }
  getWords(amount: number): string {
    return this.numberToSpanishWords(Math.round(amount));
  }
  getInvoiceGross(amount: number): number {
    return Math.round((amount / 1.19) * 100) / 100;
  }
  getInvoiceIva(amount: number): number {
    const gross = this.getInvoiceGross(amount);
    return Math.round((amount - gross) * 100) / 100;
  }
  getTotalGross(group: ClientInvoiceGroup): number {
    return group.facturas.reduce((sum, f) => sum + this.getInvoiceGross(f.valor_servicio), 0);
  }
  getTotalIva(group: ClientInvoiceGroup): number {
    return group.facturas.reduce((sum, f) => sum + this.getInvoiceIva(f.valor_servicio), 0);
  }
  getConsolidatedConsecutive(group: ClientInvoiceGroup): string {
    if (group.facturas.length === 1) {
      const f = group.facturas[0];
      return `${f.tipo === 'F20' ? 'FEMG' : 'FESP'} ${f.consecutivo}`;
    }
    const f0 = group.facturas[0];
    return `${f0.tipo === 'F20' ? 'FEMG' : 'FESP'} ${f0.consecutivo} (+${group.facturas.length - 1} DOCS)`;
  }
  getConsolidatedIssueDate(group: ClientInvoiceGroup): string {
    return group.facturas[0]?.fecha_emision || new Date().toISOString().slice(0, 10);
  }
  getConsolidatedDueDate(group: ClientInvoiceGroup): string {
    return group.facturas[0]?.fecha_vencimiento || this.getConsolidatedIssueDate(group);
  }
  private numberToSpanishWords(n: number): string {
    if (isNaN(n) || n === 0) return 'Cero';
    if (n < 0) return 'Menos ' + this.numberToSpanishWords(Math.abs(n));
    n = Math.floor(n);
    const units = ['', 'Un', 'Dos', 'Tres', 'Cuatro', 'Cinco', 'Seis', 'Siete', 'Ocho', 'Nueve',
      'Diez', 'Once', 'Doce', 'Trece', 'Catorce', 'Quince', 'Dieciséis', 'Diecisiete', 'Dieciocho', 'Diecinueve', 'Veinte',
      'Veintiuno', 'Veintidós', 'Veintitrés', 'Veinticuatro', 'Veinticinco', 'Veintiséis', 'Veintisiete', 'Veintiocho', 'Veintinueve'];
    const tens = ['', '', '', 'Treinta', 'Cuarenta', 'Cincuenta', 'Sesenta', 'Setenta', 'Ochenta', 'Noventa'];
    const hundreds = ['', 'Ciento', 'Doscientos', 'Trescientos', 'Cuatrocientos', 'Quinientos', 'Seiscientos', 'Setecientos', 'Ochocientos', 'Novecientos'];

    if (n === 100) return 'Cien';
    if (n < 30) return units[n];
    if (n < 100) {
      const t = Math.floor(n / 10);
      const u = n % 10;
      return tens[t] + (u > 0 ? ' Y ' + units[u] : '');
    }
    if (n < 1000) {
      const h = Math.floor(n / 100);
      const rem = n % 100;
      return hundreds[h] + (rem > 0 ? ' ' + this.numberToSpanishWords(rem) : '');
    }
    if (n < 1000000) {
      const thousands = Math.floor(n / 1000);
      const rem = n % 1000;
      const prefix = thousands === 1 ? 'Mil' : this.numberToSpanishWords(thousands) + ' Mil';
      return prefix + (rem > 0 ? ' ' + this.numberToSpanishWords(rem) : '');
    }
    if (n < 1000000000) {
      const millions = Math.floor(n / 1000000);
      const rem = n % 1000000;
      const prefix = millions === 1 ? 'Un Millón' : this.numberToSpanishWords(millions) + ' Millones';
      return prefix + (rem > 0 ? ' ' + this.numberToSpanishWords(rem) : '');
    }
    return String(n);
  }
  // Generación de la factura consolidada de un cliente para un periodo (POST /facturas/generar)
  readonly showGenerate = signal(false);
  readonly generating = signal(false);
  generateForm: { nit: string; period: string; type: 'F1' | 'F20' } = { nit: '', period: '', type: 'F1' };

  toggleGenerate(): void { this.showGenerate.update(open => !open); }

  generateInvoice(): void {
    const nit = this.generateForm.nit.trim();
    const period = this.generateForm.period;
    if (!nit || !/^\d{4}-\d{2}$/.test(period)) {
      this.notifications.error('Indique el NIT y el periodo a facturar.'); return;
    }
    this.generating.set(true);
    this.importer.generateInvoices({ periodoCodigo: period, tipoFacturacion: this.generateForm.type, nit })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: res => {
          this.generating.set(false);
          const created = res.facturas?.[0];
          if (!created) { this.notifications.error('El servidor no devolvió la factura generada.'); return; }
          this.notifications.success(`Factura ${created.numeroFactura} generada para el periodo ${period}.`);
          void this.router.navigate(['/consultar-facturas', created.id]);
        },
        error: err => {
          this.generating.set(false);
          this.notifications.error(err?.error?.mensaje || 'No se pudo generar la factura.');
        }
      });
  }

  importFile(event: Event): void {
    const input=event.target as HTMLInputElement; const file=input.files?.[0]; if(!file)return;
    input.value='';
    if(file.size>20*1024*1024 || !/\.(xlsx|xls|xlsm|json)$/i.test(file.name)) {
      this.notifications.error('Seleccione Excel o JSON, de hasta 20 MB.'); return;
    }
    this.importing.set(true);
    this.importer.importOfficialInvoices(file).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next:ids=>{this.importing.set(false);this.notifications.success(`${ids.length} facturas verificadas. Consulte nuevos y repetidos en el historial.`);this.load();},
      error:err=>{this.importing.set(false);this.notifications.error(err?.error?.mensaje||'No se pudo importar el archivo. Revise el historial.');}});
  }
}
