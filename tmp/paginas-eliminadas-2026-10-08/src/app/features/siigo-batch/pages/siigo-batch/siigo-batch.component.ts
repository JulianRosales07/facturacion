import { FileDownloadService } from '../../../../core/services/file-download.service';
import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { BatchService } from '../../../../core/services/batch.service';
import { LoteResponseDto, GenerarLoteRequest } from '../../../../core/models/batch.model';
import { BillingType, BillingCutType } from '../../../../core/models/invoice.model';
import { AppDatePipe } from '../../../../shared/pipes/app-date.pipe';
import { UiCardComponent } from '../../../../shared/components/ui-card/ui-card.component';
import { UiButtonComponent } from '../../../../shared/components/ui-button/ui-button.component';
import { UiBadgeComponent } from '../../../../shared/components/ui-badge/ui-badge.component';
import { UiSpinnerComponent } from '../../../../shared/components/ui-spinner/ui-spinner.component';
import { ErrorBannerComponent } from '../../../../shared/components/error-banner/error-banner.component';
import { NotificationService } from '../../../../core/services/notification.service';

@Component({
  selector: 'app-siigo-batch',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    AppDatePipe,
    UiCardComponent,
    UiBadgeComponent,
    UiSpinnerComponent,
    ErrorBannerComponent
  ],
  templateUrl: './siigo-batch.component.html',
  styleUrls: ['./siigo-batch.component.scss']
})
export class SiigoBatchComponent implements OnInit {
  private readonly downloads = inject(FileDownloadService);
  private readonly batchService = inject(BatchService);
  private readonly notification = inject(NotificationService);

  readonly batches = signal<LoteResponseDto[]>([]);
  readonly loading = signal<boolean>(true);
  readonly errorMessage = signal<string | null>(null);

  // Parámetros de Generación
  periodCode = '2026-09';
  billingType: BillingType = 'F1';
  cutType: BillingCutType = 'ADVANCE';
  readonly isGenerating = signal<boolean>(false);
  readonly isProcessingFolder = signal<boolean>(false);

  ngOnInit(): void {
    this.loadBatches();
  }

  loadBatches(): void {
    this.loading.set(true);
    this.errorMessage.set(null);

    this.batchService.listarLotes().subscribe({
      next: (data) => {
        this.batches.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.errorMessage.set(err?.error?.mensaje || 'Error al listar los lotes Siigo.');
      }
    });
  }

  generateBatch(): void {
    if (!this.periodCode) {
      this.notification.warning('Seleccione el periodo para generar el lote.');
      return;
    }

    this.isGenerating.set(true);
    const req: GenerarLoteRequest = {
      periodo: this.periodCode,
      tipoFacturacion: this.billingType,
      tipoCorte: this.cutType
    };

    this.batchService.generarLote(req).subscribe({
      next: (batch) => {
        this.isGenerating.set(false);
        this.notification.success(`Lote contable ${batch.nombreArchivo} generado con éxito.`);
        this.loadBatches();
      },
      error: (err) => {
        this.isGenerating.set(false);
        this.notification.error(err?.error?.mensaje || 'Error al generar lote');
      }
    });
  }

  downloadBatchFile(id: string): void {
    this.downloads.download(this.batchService.getArchivoLoteUrl(id), `lote-${id}.csv`);
  }

  confirmBatch(id: string): void {
    this.batchService.confirmarLote(id).subscribe({
      next: () => {
        this.notification.success('Lote confirmado exitosamente contra Siigo.');
        this.loadBatches();
      },
      error: (err) => {
        this.notification.error(err?.error?.mensaje || 'Error al confirmar lote');
      }
    });
  }

  processSharedFolder(): void {
    this.isProcessingFolder.set(true);
    this.batchService.procesarCarpeta().subscribe({
      next: (res) => {
        this.isProcessingFolder.set(false);
        this.notification.success(`Carpeta procesada: ${res.archivosProcesados} archivos, ${res.facturasActualizadas} facturas actualizadas.`);
        this.loadBatches();
      },
      error: (err) => {
        this.isProcessingFolder.set(false);
        this.notification.error(err?.error?.mensaje || 'Error al procesar carpeta compartida');
      }
    });
  }
}
