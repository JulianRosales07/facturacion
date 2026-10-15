import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { CarteraService } from '../../../../core/services/cartera.service';
import { CarteraDiscrepancy, CarteraSyncSummary } from '../../../../core/models/cartera.model';
import { CopCurrencyPipe } from '../../../../shared/pipes/cop-currency.pipe';
import { AppDatePipe } from '../../../../shared/pipes/app-date.pipe';
import { UiCardComponent } from '../../../../shared/components/ui-card/ui-card.component';
import { UiButtonComponent } from '../../../../shared/components/ui-button/ui-button.component';
import { UiBadgeComponent } from '../../../../shared/components/ui-badge/ui-badge.component';
import { UiSpinnerComponent } from '../../../../shared/components/ui-spinner/ui-spinner.component';
import { ErrorBannerComponent } from '../../../../shared/components/error-banner/error-banner.component';
import { NotificationService } from '../../../../core/services/notification.service';

@Component({
  selector: 'app-cartera',
  standalone: true,
  imports: [
    CommonModule,
    MatIconModule,
    CopCurrencyPipe,
    AppDatePipe,
    UiCardComponent,
    UiSpinnerComponent,
    ErrorBannerComponent
  ],
  templateUrl: './cartera.component.html',
  styleUrls: ['./cartera.component.scss']
})
export class CarteraComponent implements OnInit {
  private readonly carteraService = inject(CarteraService);
  private readonly notification = inject(NotificationService);

  readonly loading = signal<boolean>(true);
  readonly isSyncing = signal<boolean>(false);
  readonly errorMessage = signal<string | null>(null);

  readonly cutoffDate = signal<string | null>(null);
  readonly discrepancies = signal<CarteraDiscrepancy[]>([]);
  readonly unresolvedRows = signal<any[]>([]);
  readonly syncSummary = signal<CarteraSyncSummary | null>(null);

  activeTab: 'reconciliation' | 'unresolved' = 'reconciliation';

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.loading.set(true);
    this.errorMessage.set(null);

    this.carteraService.getReconciliation().subscribe({
      next: (rec: any) => {
        this.cutoffDate.set(rec.fechaCorte || null);
        this.discrepancies.set(rec.discrepancias || []);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.errorMessage.set(err?.error?.mensaje || 'Error al cargar conciliación de cartera.');
      }
    });

    this.carteraService.getUnresolved().subscribe({
      next: (unres: any) => {
        const rows = unres.filas || unres.unresolved || [];
        this.unresolvedRows.set(rows);
      },
      error: () => this.unresolvedRows.set([])
    });
  }

  syncNow(): void {
    this.isSyncing.set(true);
    this.carteraService.sincronizar().subscribe({
      next: (summary) => {
        this.isSyncing.set(false);
        this.syncSummary.set(summary);
        this.notification.success(`Sincronización completada: ${summary.filasAplicadas} filas aplicadas.`);
        this.loadData();
      },
      error: (err) => {
        this.isSyncing.set(false);
        this.notification.error(err?.error?.mensaje || 'Error al sincronizar cortes de cartera');
      }
    });
  }
}
