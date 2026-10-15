import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NotificationService } from '../../../../core/services/notification.service';
import { ImportOrdersService } from '../../../../core/services/import-orders.service';
import { ImportHistoryItem, NitRecord } from '../../../../core/models/import-order.model';

import { FileUploadComponent } from '../../../../shared/components/file-upload';
import { NoveltiesPanelComponent } from '../../../../shared/components/novelties-panel/novelties-panel.component';

@Component({
  selector: 'app-import-orders',
  standalone: true,
  imports: [CommonModule, FormsModule, FileUploadComponent, NoveltiesPanelComponent],
  templateUrl: './import-orders.component.html',
  styleUrls: ['./import-orders.component.scss']
})
export class ImportOrdersComponent implements OnInit {
  private readonly notification = inject(NotificationService);
  private readonly importService = inject(ImportOrdersService);

  readonly isDragOver = signal(false);
  readonly isUploading = signal(false);
  readonly uploadProgressText = signal('');

  // Server Folder Path State
  readonly serverFolderPath = signal<string>('./storage/siigo_compartida');
  readonly resolvedAbsolutePath = signal<string>('');
  readonly isPathAccessible = signal<boolean | null>(null);
  readonly pendingFilesCount = signal<number>(0);
  readonly pendingFilesList = signal<string[]>([]);
  readonly isCheckingPath = signal<boolean>(false);

  readonly autoReadHourly = signal(false);
  /** Arranca en el mes en curso; el usuario lo cambia antes de cargar el archivo si es otro periodo. */
  readonly currentPeriod = signal<string>(new Date().toISOString().slice(0, 7));

  /** Cliente consultado en "Novedades por cliente"; tras importar un archivo de un solo NIT se llena solo. */
  readonly noveltyNit = signal('');
  noveltyNitInput = '';

  queryNovelties(): void {
    const nit = this.noveltyNitInput.trim().replace(/[.s]/g, '').split('-')[0];
    this.noveltyNit.set(nit);
  }

  /** El periodo define a qué ciclo quedan asignadas las novedades del archivo. */
  setPeriod(value: string): void {
    if (/^\d{4}-\d{2}$/.test(value)) this.currentPeriod.set(value);
  }

  readonly historyFilter = signal<'ALL' | 'EXCEL' | 'CARPETA'>('ALL');
  readonly history = signal<ImportHistoryItem[]>([]);
  readonly importedRecords = signal<NitRecord[]>([]);

  readonly pageSizes = [10, 25, 50, 100];
  readonly recordsPageSize = signal(10);
  readonly recordsPage = signal(0);

  readonly recordsTotalPages = computed(() => {
    const total = this.importedRecords().length;
    const size = this.recordsPageSize();
    return total === 0 ? 0 : Math.ceil(total / size);
  });

  readonly paginatedRecords = computed(() => {
    const page = this.recordsPage();
    const size = this.recordsPageSize();
    const records = this.importedRecords();
    const start = page * size;
    return records.slice(start, start + size);
  });

  changeRecordsPageSize(size: number): void {
    this.recordsPageSize.set(size);
    this.recordsPage.set(0);
  }

  goToRecordsPage(page: number): void {
    if (page >= 0 && page < this.recordsTotalPages()) {
      this.recordsPage.set(page);
    }
  }

  /** Páginas (base 0) a mostrar: primera, última y vecinas de la actual; null marca un salto (…). */
  pageNumbers(current: number, total: number): (number | null)[] {
    if (total <= 7) return Array.from({ length: total }, (_, i) => i);
    const pages = new Set([0, total - 1, current - 1, current, current + 1].filter(p => p >= 0 && p < total));
    if (current <= 3) [1, 2, 3, 4].forEach(p => pages.add(p));
    if (current >= total - 4) [total - 5, total - 4, total - 3, total - 2].forEach(p => pages.add(p));
    const sorted = [...pages].sort((a, b) => a - b);
    return sorted.flatMap((p, i) => (i > 0 && p - sorted[i - 1] > 1 ? [null, p] : [p]));
  }

  readonly filteredHistory = computed(() => {
    const filter = this.historyFilter();
    const list = this.history();
    if (filter === 'ALL') return list;
    if (filter === 'EXCEL') {
      return list.filter(item =>
        item.method.toLowerCase().includes('excel') ||
        item.method.toLowerCase().includes('archivo')
      );
    }
    if (filter === 'CARPETA') {
      return list.filter(item =>
        item.method.toLowerCase().includes('carpeta') ||
        item.method.toLowerCase().includes('red') ||
        item.method.toLowerCase().includes('servidor')
      );
    }
    return list;
  });

  ngOnInit(): void {
    this.checkFolderConfig();
    this.loadHistory();
    this.loadRecentRecords();
  }

  /**
   * Verify server folder path and query pending files directly from the backend
   */
  checkFolderConfig(customPath?: string): void {
    const pathToTest = (customPath !== undefined ? customPath : this.serverFolderPath()).trim();
    this.isCheckingPath.set(true);

    this.importService.getFolderConfig(pathToTest).subscribe({
      next: (config) => {
        this.isCheckingPath.set(false);
        this.serverFolderPath.set(config.configuredPath);
        this.resolvedAbsolutePath.set(config.absolutePath);
        this.isPathAccessible.set(config.accessible);
        this.pendingFilesCount.set(config.filesCount);
        // El estado de la ruta se muestra en la tarjeta; no se notifica con un aviso emergente.
        this.pendingFilesList.set(config.pendingFiles || []);
      },
      error: () => {
        this.isCheckingPath.set(false);
        this.isPathAccessible.set(false);
        this.pendingFilesCount.set(0);
        this.pendingFilesList.set([]);
        this.resolvedAbsolutePath.set('');
      }
    });
  }

  setServerPath(newPath: string): void {
    this.serverFolderPath.set(newPath);
    this.checkFolderConfig(newPath);
  }

  /**
   * Instruct backend server to consume all files from the server folder path
   */
  onReadFolderNow(): void {
    const path = this.serverFolderPath().trim();
    if (!path) {
      this.notification.warning('Debes especificar la ruta de la carpeta en el servidor');
      return;
    }

    this.isUploading.set(true);
    this.uploadProgressText.set(`El servidor está consumiendo la ruta "${path}" y guardando en base de datos...`);

    this.importService.importServerFolder(path, this.currentPeriod(), 'SPE').subscribe({
      next: (res) => {
        this.isUploading.set(false);
        this.uploadProgressText.set('');

        if (res.totalRecords > 0) {
          this.notification.success(
            `¡Carpeta del servidor procesada! Se incorporaron ${res.totalRecords} registros a la base de datos (${res.errorRecords} errores).`
          );
        } else {
          this.notification.info(
            'Se escaneó la carpeta en el servidor. No se encontraron nuevos archivos de órdenes pendientes.'
          );
        }

        this.checkFolderConfig();
        this.loadHistory();
        if (res.records?.length === 1) {
          this.noveltyNitInput = res.records[0].nit;
          this.queryNovelties(); // muestra de una vez qué pasó con las novedades del archivo
        }
        if (res.records && res.records.length > 0) {
          this.importedRecords.set(res.records);
        } else {
          this.loadRecentRecords();
        }
      },
      error: (err) => {
        this.isUploading.set(false);
        this.uploadProgressText.set('');
        this.notification.error(`No se pudo procesar la carpeta "${path}". ${this.serverErrorDetail(err)}`);
        this.loadHistory();
      }
    });
  }

  loadHistory(): void {
    this.importService.getHistory().subscribe(items => this.history.set(items ?? []));
  }

  loadRecentRecords(): void {
    this.importService.getRecentRecords().subscribe(records => this.importedRecords.set(records ?? []));
  }

  /** Motivo que devolvió el servidor; sin él, se aclara que nada quedó guardado. */
  private serverErrorDetail(err: unknown): string {
    const body = (err as { error?: { messages?: string[]; mensaje?: string; message?: string } })?.error;
    const detail = body?.messages?.join(' · ') || body?.mensaje || body?.message;
    return detail ? detail : 'El servidor no respondió; no se guardó ningún registro.';
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver.set(false);

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this.uploadSingleFile(files[0]);
    }
  }

  onFilesAccepted(files: File[]): void {
    if (files.length > 0) {
      this.uploadSingleFile(files[0]);
    }
  }

  onFileInputChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.uploadSingleFile(input.files[0]);
      input.value = '';
    }
  }

  private uploadSingleFile(file: File): void {
    const fileName = file.name;
    const isExcel = fileName.toLowerCase().endsWith('.xlsx') || fileName.toLowerCase().endsWith('.xls');
    const method = isExcel ? 'EXCEL' : 'ARCHIVO_MANUAL';

    this.isUploading.set(true);
    this.uploadProgressText.set(`Importando archivo "${fileName}" y guardando en base de datos...`);

    this.importService.uploadFile(file, this.currentPeriod(), 'SPE', method).subscribe({
      next: (res) => {
        this.isUploading.set(false);
        this.uploadProgressText.set('');

        // Motivo de las filas rechazadas (p. ej. una novedad con fecha fuera del periodo elegido).
        const rowErrors = (res.messages ?? []).filter(m => m.startsWith('Fila') || m.startsWith('Error'));
        const errorDetail = rowErrors.length ? ` ${rowErrors.slice(0, 3).join(' · ')}` : '';
        if (res.errorRecords > 0 && res.totalRecords === res.errorRecords) {
          this.notification.error(
            `Error importando "${fileName}": ${res.errorRecords} filas con inconsistencias.${errorDetail}`
          );
        } else if (res.errorRecords > 0) {
          this.notification.warning(
            `Archivo "${fileName}" importado con alertas: ${res.totalRecords - res.errorRecords} válidos y ${res.errorRecords} con error.${errorDetail}`
          );
        } else {
          this.notification.success(
            `¡Archivo "${fileName}" importado con éxito! Se almacenaron ${res.totalRecords} registros en la base de datos.`
          );
        }

        // Efecto de las novedades sobre las facturas en preparación (actualizada, sin factura o no editable).
        const invoiceMessages = (res.messages ?? []).filter(m => m.startsWith('Factura') || m.startsWith('El cliente'));
        if (invoiceMessages.length) {
          this.notification.info(invoiceMessages.join(' · '));
        }

        this.loadHistory();
        if (res.records?.length === 1) {
          this.noveltyNitInput = res.records[0].nit;
          this.queryNovelties(); // muestra de una vez qué pasó con las novedades del archivo
        }
        if (res.records && res.records.length > 0) {
          this.importedRecords.set(res.records);
        } else {
          this.loadRecentRecords();
        }
      },
      error: (err) => {
        this.isUploading.set(false);
        this.uploadProgressText.set('');
        this.notification.error(`No se importó "${fileName}". ${this.serverErrorDetail(err)}`);
        this.loadHistory();
      }
    });
  }

  scrollToMethods(): void {
    const el = document.getElementById('folder-section');
    if (el) {
      el.scrollIntoView({ behavior: 'smooth' });
    }
  }

  scrollToUpload(): void {
    const el = document.getElementById('upload-section');
    if (el) {
      el.scrollIntoView({ behavior: 'smooth' });
    }
  }
}
