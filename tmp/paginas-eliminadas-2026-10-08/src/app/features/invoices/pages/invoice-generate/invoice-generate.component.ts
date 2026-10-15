import { Component, OnInit, ElementRef, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { InvoiceService } from '../../../../core/services/invoice.service';
import {
  BillingType,
  BillingCutType,
  FacturaResumenDto,
  CorporateClientOption,
  ClientNoveltiesSummary,
  ClientNoveltyDetail
} from '../../../../core/models/invoice.model';
import { CopCurrencyPipe } from '../../../../shared/pipes/cop-currency.pipe';
import { AppDatePipe } from '../../../../shared/pipes/app-date.pipe';
import { UiCardComponent } from '../../../../shared/components/ui-card/ui-card.component';
import { UiSpinnerComponent } from '../../../../shared/components/ui-spinner/ui-spinner.component';
import { NotificationService } from '../../../../core/services/notification.service';

@Component({
  selector: 'app-invoice-generate',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    MatIconModule,
    CopCurrencyPipe,
    UiCardComponent,
    UiSpinnerComponent
  ],
  templateUrl: './invoice-generate.component.html',
  styleUrls: ['./invoice-generate.component.scss']
})
export class InvoiceGenerateComponent implements OnInit {
  private readonly invoiceService = inject(InvoiceService);
  private readonly notification = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly elementRef = inject(ElementRef);

  // Parámetros de emisión
  periodCode = '2026-09';
  billingType: BillingType = 'F1';
  cutType: BillingCutType = 'ADVANCE';
  clientNit = '';

  // Lista y búsqueda de clientes corporativos
  readonly clients = signal<CorporateClientOption[]>([]);
  readonly isLoadingClients = signal<boolean>(false);
  searchQuery = '';

  // Cliente seleccionado para facturar
  readonly selectedClient = signal<CorporateClientOption | null>(null);

  // Cliente abierto en vista completa en toda la página
  readonly detailClient = signal<CorporateClientOption | null>(null);

  // Novedades consumidas del cliente para el periodo
  readonly noveltiesSummary = signal<ClientNoveltiesSummary | null>(null);
  readonly isLoadingNovelties = signal<boolean>(false);
  readonly isConsumingNovelties = signal<boolean>(false);

  // Historial de facturas emitidas del cliente
  readonly clientInvoices = signal<FacturaResumenDto[]>([]);
  readonly isLoadingHistory = signal<boolean>(false);

  // Filtros del historial de facturas
  historyMonthFilter = '';
  historyStartDateFilter = '';
  historyEndDateFilter = '';
  historyStatusFilter = '';

  // Lista de meses disponibles en el historial del cliente para filtro rápido
  readonly availableHistoryMonths = computed(() => {
    const list = this.clientInvoices();
    const months = new Set<string>();
    list.forEach(inv => {
      if (inv.periodoCodigo) months.add(inv.periodoCodigo);
      if (inv.fechaEmision) months.add(inv.fechaEmision.slice(0, 7));
    });
    if (this.periodCode) months.add(this.periodCode);
    return Array.from(months).filter(m => /^\d{4}-\d{2}$/.test(m)).sort().reverse();
  });

  // Facturas del historial filtradas en tiempo real por mes, rango de fechas y estado
  readonly filteredHistoryInvoices = computed(() => {
    let list = this.clientInvoices();
    const month = this.historyMonthFilter.trim();
    const start = this.historyStartDateFilter;
    const end = this.historyEndDateFilter;
    const status = this.historyStatusFilter.trim().toUpperCase();

    if (month) {
      list = list.filter(inv =>
        inv.periodoCodigo === month ||
        (inv.fechaEmision && inv.fechaEmision.startsWith(month))
      );
    }

    if (start) {
      list = list.filter(inv => inv.fechaEmision >= start);
    }

    if (end) {
      list = list.filter(inv => inv.fechaEmision <= end);
    }

    if (status) {
      list = list.filter(inv => inv.estado?.toUpperCase() === status);
    }

    return list;
  });

  // Estado de previsualización antes de emitir
  readonly isPreviewOpen = signal<boolean>(false);

  // Computados para previsualización de borrador
  readonly previewPrefix = computed(() => (this.billingType === 'F20' ? 'FEMG' : 'FESP'));
  readonly previewInvoiceNumber = computed(() => {
    const periodClean = (this.periodCode || '2026-09').replace('-', '');
    return `${this.previewPrefix()}-${periodClean}-BORRADOR`;
  });
  readonly previewIssueDate = computed(() => new Date().toISOString().slice(0, 10));
  readonly previewDueDate = computed(() => {
    const d = new Date();
    d.setDate(d.getDate() + 30);
    return d.toISOString().slice(0, 10);
  });

  readonly previewSubtotalBase = computed(() => {
    return this.noveltiesSummary()?.baseMensual || this.detailClient()?.totalMonthlyValue || 0;
  });
  readonly previewAjustesNovedades = computed(() => {
    return this.noveltiesSummary()?.ajusteNovedades || 0;
  });
  readonly previewCuotasInstalacion = computed(() => {
    return this.noveltiesSummary()?.cuotasInstalacion || 0;
  });
  readonly previewTotal = computed(() => {
    return this.noveltiesSummary()?.totalEstimadoFactura || this.detailClient()?.totalMonthlyValue || 0;
  });
  readonly previewIva = computed(() => Math.round(this.previewTotal() * 0.19));
  readonly previewReteFuente = computed(() => Math.round(this.previewTotal() * 0.04));
  readonly previewReteIca = computed(() => Math.round(this.previewTotal() * 0.00966));

  readonly previewItems = computed(() => {
    const client = this.detailClient();
    if (!client) return [];
    const items: Array<{
      item: number;
      codigo: string;
      descripcion: string;
      tipo: 'CANON_SEDE' | 'NOVEDAD';
      cant: number;
      und: string;
      valorUnitario: number;
      valorTotal: number;
      diasProrrateo?: number;
      tipoNovedad?: string;
    }> = [];

    let idx = 1;
    if (client.sedes) {
      client.sedes.forEach(s => {
        items.push({
          item: idx++,
          codigo: s.codigoOrden,
          descripcion: `Canon Mensual - Sede ${s.nombreSede} (${s.anchoBanda || 'Enlace Corporativo'})`,
          tipo: 'CANON_SEDE',
          cant: 1,
          und: 'MES',
          valorUnitario: s.valorMensual,
          valorTotal: s.valorMensual
        });
      });
    }

    const novSummary = this.noveltiesSummary();
    if (novSummary && novSummary.novedades) {
      novSummary.novedades.forEach(n => {
        items.push({
          item: idx++,
          codigo: n.codigoOrden || `NOV-${n.id}`,
          descripcion: `${n.tipoNovedad}: ${n.sedeNombre} - ${n.observacion}`,
          tipo: 'NOVEDAD',
          cant: 1,
          und: 'ACT',
          valorUnitario: n.valorAjuste,
          valorTotal: n.valorAjuste,
          diasProrrateo: n.diasProrrateo,
          tipoNovedad: n.tipoNovedad
        });
      });
    }

    return items;
  });

  // Estado del proceso de generación
  readonly isProcessing = signal<boolean>(false);
  readonly generatedInvoices = signal<FacturaResumenDto[]>([]);
  readonly processCompleted = signal<boolean>(false);

  // Paginación de clientes corporativos
  readonly currentPage = signal<number>(1);
  readonly pageSize = signal<number>(15);

  // Filtro de clientes en tiempo real por NIT o Nombre
  readonly filteredClients = computed(() => {
    const query = this.searchQuery.trim().toLowerCase();
    const list = this.clients();
    if (!query) {
      return list;
    }
    return list.filter(c =>
      (c.nit && c.nit.toLowerCase().includes(query)) ||
      (c.razonSocial && c.razonSocial.toLowerCase().includes(query)) ||
      (c.municipio && c.municipio.toLowerCase().includes(query))
    );
  });

  readonly totalPages = computed(() => Math.max(1, Math.ceil(this.filteredClients().length / this.pageSize())));

  readonly startDisplay = computed(() => ((this.currentPage() - 1) * this.pageSize()) + 1);
  readonly minDisplay = computed(() => Math.min(this.currentPage() * this.pageSize(), this.filteredClients().length));

  readonly paginatedClients = computed(() => {
    const page = Math.min(this.currentPage(), this.totalPages());
    const start = (page - 1) * this.pageSize();
    return this.filteredClients().slice(start, start + this.pageSize());
  });

  goToPage(page: number): void {
    if (page >= 1 && page <= this.totalPages()) {
      this.currentPage.set(page);
    }
  }

  nextPage(): void {
    if (this.currentPage() < this.totalPages()) {
      this.currentPage.update(p => p + 1);
    }
  }

  prevPage(): void {
    if (this.currentPage() > 1) {
      this.currentPage.update(p => p - 1);
    }
  }

  onSearchChange(): void {
    this.currentPage.set(1);
  }

  ngOnInit(): void {
    this.loadCorporateClients();
  }

  loadCorporateClients(): void {
    this.isLoadingClients.set(true);
    this.invoiceService.getCorporateClients().subscribe({
      next: (data) => {
        this.clients.set(data || []);
        this.isLoadingClients.set(false);
      },
      error: () => {
        this.isLoadingClients.set(false);
      },
    });
  }

  // Selección para facturar
  selectClientForBilling(client: CorporateClientOption): void {
    this.selectedClient.set(client);
    this.clientNit = client.nit;
    if (client.tipoFacturacion) {
      this.billingType = client.tipoFacturacion;
    }
    this.notification.success(`Seleccionado: ${client.razonSocial} (NIT: ${client.nit}) para emisión.`);
  }

  clearBillingSelection(): void {
    this.selectedClient.set(null);
    this.clientNit = '';
  }

  // Abrir vista completa del cliente en toda la página
  openClientDetails(client: CorporateClientOption): void {
    this.detailClient.set(client);
    this.resetHistoryFilters();
    this.loadNoveltiesForDetail(client.nit);
    this.loadClientHistory(client.nit);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  closeClientDetails(): void {
    this.detailClient.set(null);
    this.noveltiesSummary.set(null);
    this.clientInvoices.set([]);
    this.resetHistoryFilters();
    this.isPreviewOpen.set(false);
  }

  openInvoicePreview(): void {
    if (!this.periodCode) {
      this.notification.warning('Debe seleccionar un periodo contable.');
      return;
    }
    const client = this.detailClient();
    if (client) {
      this.selectClientForBilling(client);
    }
    this.isPreviewOpen.set(true);
    setTimeout(() => {
      const el = this.elementRef.nativeElement.querySelector('#invoicePreviewSection');
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    }, 50);
  }

  closeInvoicePreview(): void {
    this.isPreviewOpen.set(false);
  }

  confirmAndEmitInvoice(): void {
    this.onSubmit();
    this.isPreviewOpen.set(false);
  }

  loadNoveltiesForDetail(nit: string): void {
    this.isLoadingNovelties.set(true);
    this.invoiceService.getClientNovelties(nit, this.periodCode).subscribe({
      next: (summary) => {
        this.noveltiesSummary.set(summary);
        this.isLoadingNovelties.set(false);
      },
      error: () => {
        this.isLoadingNovelties.set(false);
      }
    });
  }

  loadClientHistory(nit: string): void {
    this.isLoadingHistory.set(true);
    this.invoiceService.getClientInvoices(nit).subscribe({
      next: (data) => {
        this.clientInvoices.set(data || []);
        this.isLoadingHistory.set(false);
      },
      error: () => {
        this.isLoadingHistory.set(false);
      }
    });
  }

  resetHistoryFilters(): void {
    this.historyMonthFilter = '';
    this.historyStartDateFilter = '';
    this.historyEndDateFilter = '';
    this.historyStatusFilter = '';
  }

  onDownloadInvoicePdf(inv: FacturaResumenDto): void {
    this.notification.info(`Consultando comprobante de Factura ${inv.numeroFactura}...`);
    this.router.navigate(['/consultar-facturas'], { queryParams: { number: inv.numeroFactura } });
  }

  // Obtener novedades asociadas a una sede específica por su código de orden
  getSiteNovelties(codigoOrden?: string): ClientNoveltyDetail[] {
    if (!codigoOrden) return [];
    const summary = this.noveltiesSummary();
    if (!summary || !summary.novedades) return [];
    return summary.novedades.filter(n => n.codigoOrden?.trim().toLowerCase() === codigoOrden.trim().toLowerCase());
  }

  getSiteNoveltiesTotal(codigoOrden?: string): number {
    const novs = this.getSiteNovelties(codigoOrden);
    return novs.reduce((sum, n) => {
      if (n.tipoImpacto === 'RESTA') return sum - n.valorAjuste;
      return sum + n.valorAjuste;
    }, 0);
  }

  // Consumir / sincronizar novedades para este cliente
  consumeNovelties(): void {
    const client = this.detailClient();
    if (!client) return;

    this.isConsumingNovelties.set(true);
    this.invoiceService.consumeClientNovelties(client.nit, this.periodCode).subscribe({
      next: (summary) => {
        this.noveltiesSummary.set(summary);
        this.isConsumingNovelties.set(false);
        this.notification.success(
          `Novedades sincronizadas: Se consumieron ${summary.totalNovedades} novedades del periodo ${this.periodCode} para ${client.razonSocial}.`
        );
      },
      error: () => {
        this.isConsumingNovelties.set(false);
        this.notification.error('Error al sincronizar novedades de órdenes.');
      }
    });
  }

  onPeriodChangeInDetail(): void {
    const client = this.detailClient();
    if (client) {
      this.loadNoveltiesForDetail(client.nit);
    }
  }

  selectFromDetails(): void {
    const client = this.detailClient();
    if (client) {
      this.selectClientForBilling(client);
      this.closeClientDetails();
    }
  }

  emitFromDetails(): void {
    const client = this.detailClient();
    if (client) {
      this.selectClientForBilling(client);
      this.onSubmit();
    }
  }

  // Entrada manual de NIT si no existe en la lista
  selectManualNit(manualTerm: string): void {
    const nit = manualTerm.trim();
    if (!nit) return;

    const match = this.clients().find(c => c.nit.toLowerCase() === nit.toLowerCase());
    if (match) {
      this.selectClientForBilling(match);
      return;
    }

    const manualClient: CorporateClientOption = {
      nit: nit,
      razonSocial: `Corporativo NIT ${nit}`,
      branchesCount: 1,
      tipoFacturacion: this.billingType,
      municipio: 'No especificado',
      totalMonthlyValue: 0,
      sedes: []
    };

    this.selectedClient.set(manualClient);
    this.clientNit = nit;
    this.notification.info(`Fijado NIT manual: ${nit}`);
  }

  // Proceso de generación
  onSubmit(): void {
    if (!this.periodCode) {
      this.notification.warning('Debe seleccionar un periodo de facturación.');
      return;
    }

    this.isProcessing.set(true);
    this.processCompleted.set(false);

    const targetNit = this.clientNit.trim() || undefined;

    this.invoiceService.generateInvoices({
      periodoCodigo: this.periodCode,
      tipoFacturacion: this.billingType,
      tipoCorte: this.cutType,
      nit: targetNit
    }).subscribe({
      next: (response) => {
        this.isProcessing.set(false);
        this.generatedInvoices.set(response.facturas || []);
        this.processCompleted.set(true);

        if (targetNit && this.selectedClient()) {
          this.notification.success(`Generación exitosa para ${this.selectedClient()?.razonSocial}: ${response.generadas} factura(s) emitida(s).`);
        } else {
          this.notification.success(`Generación exitosa: ${response.generadas} facturas consolidadas creadas.`);
        }

        // Recargar historial del cliente para reflejar de inmediato la factura recién emitida
        const activeClient = this.detailClient() || this.selectedClient();
        if (activeClient) {
          this.loadClientHistory(activeClient.nit);
        }

        setTimeout(() => {
          const resultsEl = this.elementRef.nativeElement.querySelector('#resultsSection');
          if (resultsEl) {
            resultsEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }
        }, 50);
      },
      error: (err) => {
        this.isProcessing.set(false);
        this.notification.error(err?.error?.mensaje || 'Error al generar la facturación consolidada.');
      }
    });
  }

  onReset(): void {
    this.processCompleted.set(false);
    this.generatedInvoices.set([]);
    this.clearBillingSelection();
    this.closeClientDetails();
    this.searchQuery = '';
  }

  onViewInvoice(id: string): void {
    this.router.navigate(['/consultar-facturas']);
  }
}
